"""Runs the REAL Django recommendation functions on fixed inputs and prints pinned vectors as JSON.

DB access is replaced at the row level (the ORM/cursor calls return fixed rows) so every piece of
Python math/branching is the original code. Run from backend/ with SECRET_KEY set.
"""
import os, sys, json, math
from datetime import datetime, timedelta, timezone as dt_tz, time as dt_time
sys.path.insert(0, os.getcwd())
os.environ.setdefault("DJANGO_SETTINGS_MODULE", "marketplace.settings")
import django
django.setup()
import django.utils.timezone as djtz

from recommendation.services import scoring as scoring_mod
from recommendation.services import vector_index as vi_mod
from recommendation.services import daily_nutrition as dn_mod
from recommendation.services import candidates as cand_mod
from recommendation.services.scoring import ScoringEngine
from recommendation.services.rerank import MMRReranker
from recommendation.services.explain import build_reasons
from recommendation.services.candidates import CandidateGenerator
from recommendation.services.daily_nutrition import DailyNutritionService, ParsedMeal
from recommendation.services.recommendation import RecommendationService
from recommendation.services.vector_index import VectorIndexService

NOW = datetime(2026, 9, 23, 12, 0, 0, tzinfo=dt_tz.utc)
OUT = {}


class FakeQS:
    def __init__(self, handler, state=None):
        self.handler = handler
        self.state = state or {"filters": [], "values": None, "annotate": None}

    def _c(self, **upd):
        st = dict(self.state)
        st.update(upd)
        return FakeQS(self.handler, st)

    def filter(self, *a, **kw):
        return self._c(filters=self.state["filters"] + [kw])

    def exclude(self, *a, **kw):
        return self

    def values(self, *args):
        return self._c(values=args)

    def values_list(self, *args, **kw):
        return self._c(values=args)

    def annotate(self, **kw):
        return self._c(annotate=tuple(kw.keys()))

    def order_by(self, *a):
        return self

    def select_related(self, *a):
        return self

    def distinct(self):
        return self

    def _rows(self):
        return list(self.handler(self.state))

    def __iter__(self):
        return iter(self._rows())

    def __bool__(self):
        return bool(self._rows())

    def __len__(self):
        return len(self._rows())


class FakeManager:
    def __init__(self, handler):
        self.handler = handler

    def filter(self, *a, **kw):
        return FakeQS(self.handler).filter(**kw)


class FakeModel:
    def __init__(self, handler):
        self.objects = FakeManager(handler)


class Obj:
    def __init__(self, **kw):
        self.__dict__.update(kw)


# ---------------------------------------------------------------------------
# 1. ScoringEngine pure helpers
# ---------------------------------------------------------------------------
eng = ScoringEngine(time_decay_lambda=0.03, recency_decay_lambda=0.02,
                    weights={"w1": 0.4, "w2": 0.2, "w3": 0.2, "w4": 0.1, "w5": 0.1},
                    penalty_gamma=0.25, penalty_correlation_threshold=0.35,
                    balanced_target=[0.3, 0.3, 0.4], favorite_recency_decay_lambda=0.03,
                    user_vector_ema_alpha=0.35)
vectors = [
    [40.0, 20.0, 10.0, 0.0, 3.0],
    [10.0, 10.0, 60.0, 0.0, 2.0],
    [12.5, 7.25, 33.3, 1.2, 9.1],
    [0.0, 0.0, 0.0, 0.5, 4.0],
    [5.0, 30.0, 5.0, 0.9, 0.0],
    [1.0, 2.0],
]
modes = ["NONE", "LOW_CARB", "HIGH_PROTEIN", "LOW_FAT", "BALANCED", "LIGHT", "weird", None]
OUT["diet_alignment"] = [[m, v, eng._diet_alignment(m, v)] for m in modes for v in vectors]
OUT["diet_level_weight"] = [[l, eng._diet_level_weight(l)] for l in ["NONE", "SOFT", "MEDIUM", "STRONG", "soft", None, "X"]]
eng_bt = ScoringEngine(time_decay_lambda=0.03, recency_decay_lambda=0.02, weights={"w1": 0.4},
                       penalty_gamma=0.25, penalty_correlation_threshold=0.35, balanced_target=[2, 1, 1])
OUT["balanced_target_normalized"] = list(eng_bt.balanced_target)
OUT["balanced_target_zero"] = list(ScoringEngine(time_decay_lambda=0.03, recency_decay_lambda=0.02, weights={},
                                                 penalty_gamma=0.25, penalty_correlation_threshold=0.35,
                                                 balanced_target=[0, 0, 0]).balanced_target)
OUT["unified_penalty_default"] = eng.unified_penalty_weight
OUT["ema"] = [
    [[1.0, 2.0, 3.0, 4.0, 5.0], [5.0, 4.0, 3.0, 2.0, 1.0], eng._apply_user_vector_ema([1.0, 2.0, 3.0, 4.0, 5.0], [5.0, 4.0, 3.0, 2.0, 1.0])],
    [[], [5.0, 4.0, 3.0, 2.0, 1.0], eng._apply_user_vector_ema([], [5.0, 4.0, 3.0, 2.0, 1.0])],
    [[1.0, 2.0, 3.0, 4.0, 5.0], [], eng._apply_user_vector_ema([1.0, 2.0, 3.0, 4.0, 5.0], [])],
    [[1.0, 2.0], [5.0, 4.0, 3.0, 2.0, 1.0], eng._apply_user_vector_ema([1.0, 2.0], [5.0, 4.0, 3.0, 2.0, 1.0])],
]
OUT["cosine"] = [[a, b, ScoringEngine._cosine_similarity(a, b)] for a, b in [
    ([1.0, 2.0, 3.0], [3.0, 2.0, 1.0]),
    ([0.3, 0.1, 0.6, 0.0012, 0.02], [25.1, 7.0, 40.2, 0.9, 2.2]),
    ([0.0, 0.0, 0.0], [1.0, 1.0, 1.0]),
    ([], [1.0]),
    ([1.0, 2.0, 3.0, 4.0, 5.0], [1.0, 2.0]),
]]

# ---------------------------------------------------------------------------
# 2. ScoringEngine.score end to end with row-level fakes
# ---------------------------------------------------------------------------
D1, D2, D3, D4 = ("11111111-1111-1111-1111-111111111111", "22222222-2222-2222-2222-222222222222",
                  "33333333-3333-3333-3333-333333333333", "44444444-4444-4444-4444-444444444444")
dishes = [
    Obj(uid=D1, avg_rating=4.5, created_at=NOW - timedelta(days=3, hours=4)),
    Obj(uid=D2, avg_rating=3.0, created_at=NOW - timedelta(days=40)),
    Obj(uid=D3, avg_rating=0.0, created_at=NOW - timedelta(days=200, seconds=17)),
    Obj(uid=D4, avg_rating=5.0, created_at=NOW + timedelta(hours=1)),
]
order_rows = [  # dish_id, quantity, order__created_at
    {"dish_id": D1, "quantity": 2, "order__created_at": NOW - timedelta(days=1)},
    {"dish_id": D1, "quantity": 1, "order__created_at": NOW - timedelta(days=10, hours=6)},
    {"dish_id": D2, "quantity": 3, "order__created_at": NOW - timedelta(days=60)},
    {"dish_id": D3, "quantity": 0, "order__created_at": NOW - timedelta(days=5)},
]
popularity_rows = [{"dish_id": D1, "count": 12}, {"dish_id": D2, "count": 3}, {"dish_id": D4, "count": 1}]
ingredient_total_rows = [{"dish_id": D1, "total": 4}, {"dish_id": D2, "total": 3}, {"dish_id": D3, "total": 2}]
ingredient_match_rows = [{"dish_id": D1, "matched": 2}, {"dish_id": D2, "matched": 3}]
favorite_rows = [{"dish_id": D2, "created_at": NOW - timedelta(days=7)}, {"dish_id": D4, "created_at": NOW - timedelta(hours=12)}]
issue_rows = [
    {"dish_id": D1, "issue": "mặn", "avg_weight": 0.8},
    {"dish_id": D1, "issue": " Cay ", "avg_weight": 0.6},
    {"dish_id": D2, "issue": "nguội", "avg_weight": 1.7},
    {"dish_id": D2, "issue": "unknown", "avg_weight": 0.9},
    {"dish_id": D3, "issue": "dầu mỡ", "avg_weight": None},
]
nutrition_rows = [  # dish_id weight protein lipid carbohydrate natri fiber
    {"dish_id": D1, "weight": 100.0, "protein": 20.0, "lipid": 5.0, "carbohydrate": 30.0, "natri": 400.0, "fiber": 2.0},
    {"dish_id": D1, "weight": 50.0, "protein": 3.0, "lipid": None, "carbohydrate": 12.5, "natri": 10.0, "fiber": None},
    {"dish_id": D2, "weight": 0.0, "protein": 8.0, "lipid": 4.0, "carbohydrate": None, "natri": 100.0, "fiber": 1.0},
    {"dish_id": D2, "weight": None, "protein": 6.0, "lipid": None, "carbohydrate": 22.0, "natri": None, "fiber": 3.0},
    {"dish_id": D3, "weight": 80.0, "protein": 1.0, "lipid": 9.0, "carbohydrate": 1.5, "natri": 5.0, "fiber": 0.5},
]
coverage_rows = [
    {"dish_id": D1, "total_count": 2, "protein_count": 2, "lipid_count": 1, "carb_count": 2, "sodium_count": 2, "fiber_count": 1},
    {"dish_id": D2, "total_count": 2, "protein_count": 2, "lipid_count": 1, "carb_count": 1, "sodium_count": 1, "fiber_count": 2},
    {"dish_id": D3, "total_count": 1, "protein_count": 1, "lipid_count": 1, "carb_count": 1, "sodium_count": 1, "fiber_count": 1},
]


def order_handler(state):
    if state["annotate"] == ("count",):
        return popularity_rows
    return order_rows


def di_handler(state):
    ann = state["annotate"]
    if ann == ("total",):
        return ingredient_total_rows
    if ann == ("matched",):
        return ingredient_match_rows
    if ann and "total_count" in ann:
        return coverage_rows
    return nutrition_rows


def review_handler(state):
    return issue_rows


import profile.models as profile_models
scoring_mod.OrderItem = FakeModel(order_handler)
scoring_mod.DishIngredient = FakeModel(di_handler)
scoring_mod.Review = FakeModel(review_handler)
profile_models.CustomerFavoriteDish = FakeModel(lambda st: favorite_rows)
scoring_mod.timezone.now = lambda: NOW  # scoring imports django.utils.timezone

issue_profile = {k: 0.0 for k in RecommendationService.DEFAULT_ISSUES}
issue_profile.update({"mặn": 0.9, "cay": 0.5, "nguội": 0.7, "dầu mỡ": 0.4})


def run_score(engine, persisted, diet_mode, diet_level, conf, fav_dishes, fav_ings):
    res = engine.score(user_id=1, dishes=dishes, favorite_dish_ids=fav_dishes, favorite_ingredient_ids=fav_ings,
                       diet_mode=diet_mode, diet_level=diet_level, issue_profile=issue_profile,
                       issue_confidence=conf, persisted_user_vector=persisted)
    return [{k: v for k, v in r.items() if k != "dish"} for r in res]


OUT["score_inputs"] = {"now": NOW.isoformat(), "issue_profile": issue_profile}
OUT["score_default"] = run_score(eng, [0.3, 0.1, 0.6, 0.0012, 0.02], "BALANCED", "STRONG", 0.62, [D2, D4], ["ing-a"])
OUT["score_no_persisted_none_diet"] = run_score(eng, [], "NONE", "NONE", 0.0, [], [])
eng_prio = ScoringEngine(time_decay_lambda=0.03, recency_decay_lambda=0.02,
                         weights={"w1": 0.4, "w2": 0.2, "w3": 0.2, "w4": 0.1, "w5": 0.1},
                         penalty_gamma=0.25, penalty_correlation_threshold=0.1,
                         balanced_target=[0.3, 0.3, 0.4], favorite_recency_decay_lambda=0.05,
                         user_vector_ema_alpha=0.8, prioritize_unordered_dishes=True)
OUT["score_prioritize_unordered"] = run_score(eng_prio, [10.0, 1.0, 2.0, 0.1, 0.0], "LIGHT", "MEDIUM", 1.0, [D1], ["x"])

# ---------------------------------------------------------------------------
# 3. MMR
# ---------------------------------------------------------------------------
mmr_items = [
    {"id": "a", "score": 0.9, "dish_vector": [1.0, 0.0, 0.0, 0.0, 0.0]},
    {"id": "b", "score": 0.85, "dish_vector": [0.9, 0.1, 0.0, 0.0, 0.0]},
    {"id": "c", "score": 0.7, "dish_vector": [0.0, 1.0, 0.0, 0.0, 0.0]},
    {"id": "d", "score": 0.7, "dish_vector": [0.0, 0.0, 1.0, 0.0, 0.0]},
    {"id": "e", "score": -0.2, "dish_vector": [0.0, 0.0, 0.0, 0.0, 0.0]},
    {"id": "f", "score": 0.6, "dish_vector": []},
    {"id": "g", "score": 0.65, "dish_vector": [0.5, 0.5, 0.5, 0.0, 0.1]},
]
OUT["mmr"] = []
for lam, take in [(0.5, 7), (0.5, 3), (0.3, 7), (1.0, 7), (0.0, 7), (1.7, 4), (-1.0, 4), (0.5, 0)]:
    r = MMRReranker(lambda_value=lam, use_pgvector=False).rerank(mmr_items, take=take)
    OUT["mmr"].append({"lambda": lam, "take": take, "order": [i["id"] for i in r]})

# ---------------------------------------------------------------------------
# 4. explain
# ---------------------------------------------------------------------------
explain_cases = [
    {"favorite_score": 0.0, "history_score": 0.0, "ingredient_match_ratio": 0.0, "issue_penalty": 0.8, "nutrition_penalty": 0.9, "base_score": 0.1},
    {"favorite_score": 0.0, "history_score": 0.0, "ingredient_match_ratio": 0.0, "issue_penalty": 0.0, "preference_nutrition_mismatch_penalty": 0.1, "base_score": 0.1},
    {"favorite_score": 0.7, "history_score": 0.6, "ingredient_match_ratio": 0.5, "issue_penalty": 0.2, "preference_nutrition_mismatch_penalty": 0.3, "diet_alignment": 0.65, "base_score": 0.6},
    {"favorite_score": 0.69, "history_score": 0.59, "ingredient_match_ratio": 0.49, "issue_penalty": 0.21, "preference_nutrition_mismatch_penalty": 0.31, "diet_alignment": 0.66, "base_score": 0.61},
    {"favorite_score": 0.1, "history_score": 0.9, "ingredient_match_ratio": 0.0, "issue_penalty": 0.5, "preference_nutrition_mismatch_penalty": 0.5, "diet_alignment": 0.1, "base_score": 0.9},
]
OUT["explain"] = [[c, build_reasons(c)] for c in explain_cases]

# ---------------------------------------------------------------------------
# 5. quotas
# ---------------------------------------------------------------------------
OUT["quotas"] = []
for pool in [300, 100, 60, 13, 7, 5, 3, 2, 1, 0]:
    for use_ann in (False, True):
        for has_vec in (False, True):
            q = CandidateGenerator(candidate_pool_size=pool)._resolve_source_quotas(use_ann=use_ann, has_user_vector=has_vec)
            OUT["quotas"].append({"pool": pool, "use_ann": use_ann, "has_vec": has_vec, "quotas": q})
OUT["vector_literal"] = [CandidateGenerator._to_vector_literal(v) for v in ([1, 2.5, 0.1, 1e-05, 123456789.125],)]

# ---------------------------------------------------------------------------
# 6. issue sensitivity profile
# ---------------------------------------------------------------------------
djtz.now = lambda: NOW
rs = RecommendationService()
issue_rows_user = [
    {"issue": "mặn", "weight": 0.9, "created_at": NOW - timedelta(days=2)},
    {"issue": "MẶN ", "weight": 0.5, "created_at": NOW - timedelta(days=30, hours=5)},
    {"issue": "man", "weight": 0.7, "created_at": NOW - timedelta(days=1)},
    {"issue": "dau-mo", "weight": 1.4, "created_at": NOW - timedelta(days=3)},
    {"issue": "giao cham", "weight": 0.05, "created_at": NOW - timedelta(days=3)},
    {"issue": "giao_cham", "weight": 0.35, "created_at": None},
    {"issue": "not an issue", "weight": 0.9, "created_at": NOW},
    {"issue": "", "weight": 0.9, "created_at": NOW},
    {"issue": "cay", "weight": None, "created_at": NOW},
    {"issue": "Cay", "weight": 0.2, "created_at": NOW + timedelta(days=1)},
]
processed = rs._preprocess(issue_rows_user)
profile, stats = rs._aggregate(processed)
conf = rs._compute_confidence(stats)
OUT["issue_profile"] = {"profile": profile, "confidence": round(conf, 3), "confidence_raw": conf, "data_points": stats["data_points"]}
OUT["classify"] = [[s, rs._classify_issue(s)] for s in ["mặn", " MẶN", "man", "dau_mo", "dầu-mỡ", "hu thiu", "xyz", "", "ít  topping", "it-topping"]]

# ---------------------------------------------------------------------------
# 7. vector index math
# ---------------------------------------------------------------------------
vi_rows = [
    {"dish_id": D1, "weight": 100.0, "protein": 20.0, "lipid": 5.0, "carbohydrate": 30.0, "natri": 400.0, "fiber": 2.0, "confidence": 0.9},
    {"dish_id": D1, "weight": 50.0, "protein": 3.0, "lipid": None, "carbohydrate": 12.5, "natri": 10.0, "fiber": None, "confidence": None},
    {"dish_id": D1, "weight": -5.0, "protein": 99.0, "lipid": 99.0, "carbohydrate": 99.0, "natri": 99.0, "fiber": 99.0, "confidence": 0.5},
    {"dish_id": D2, "weight": 0.0, "protein": 8.0, "lipid": 4.0, "carbohydrate": None, "natri": 100.0, "fiber": 1.0, "confidence": 0.0},
    {"dish_id": D2, "weight": 30.0, "protein": 6.0, "lipid": 1.0, "carbohydrate": 22.0, "natri": 2.0, "fiber": 3.0, "confidence": 1.5},
    {"dish_id": D3, "weight": 80.0, "protein": 1.0, "lipid": 9.0, "carbohydrate": 1.5, "natri": 5.0, "fiber": 0.5, "confidence": 0.25},
    {"dish_id": D4, "weight": 10.0, "protein": 1.0, "lipid": 1.0, "carbohydrate": 1.0, "natri": 1.0, "fiber": 1.0, "confidence": 1.2},
]
captured = {}


class FakeCursor:
    def __enter__(self): return self
    def __exit__(self, *a): return False
    def executemany(self, sql, payload): captured["payload"] = list(payload)
    def execute(self, *a): pass


vi_mod.DishIngredient = FakeModel(lambda st: vi_rows)
vi_mod.connection = Obj(cursor=lambda: FakeCursor())
n = VectorIndexService().refresh_dish_vectors()
OUT["dish_vectors"] = [[p[0], VectorIndexService._parse_vector_literal(p[1]), p[2]] for p in captured["payload"]]
OUT["dish_vectors_literals"] = [p[1] for p in captured["payload"]]

hist_rows = [
    {"dish_id": D1, "quantity": 2, "order__created_at": NOW - timedelta(days=1, hours=2)},
    {"dish_id": D2, "quantity": 1, "order__created_at": NOW - timedelta(days=20)},
    {"dish_id": D1, "quantity": 0, "order__created_at": NOW - timedelta(days=100)},
    {"dish_id": D3, "quantity": 5, "order__created_at": NOW - timedelta(days=2)},  # no vector
]
vi_mod.OrderItem = FakeModel(lambda st: hist_rows)
vi_mod.timezone.now = lambda: NOW
svc = VectorIndexService()
svc._get_dish_vectors = lambda ids: {D1: [21.5, 5.25, 31.0, 0.401, 1.9], D2: [6.0, 1.0, 22.0, 0.002, 3.0]}
OUT["user_vector_history"] = {"lambda": 0.03, "vector": svc._build_user_vector_from_history(user_id=1, decay_lambda=0.03)}
OUT["user_vector_history_l01"] = svc._build_user_vector_from_history(user_id=1, decay_lambda=0.1)
OUT["parse_literal"] = [[s, VectorIndexService._parse_vector_literal(s)] for s in ["[1,2.5,3e-05]", "[]", "", " [0.1, 0.2] ", "[1,x]", "1,2"]]

# ---------------------------------------------------------------------------
# 8. daily nutrition pure functions
# ---------------------------------------------------------------------------
dn = DailyNutritionService()
targets = []
for g in ["MALE", "FEMALE", "OTHER"]:
    for act in ["SEDENTARY", "LIGHT", "MODERATE", "ACTIVE", "VERY_ACTIVE", "BOGUS"]:
        for goal in ["MAINTAIN", "LOSE", "GAIN"]:
            for (age, w, h) in [(30, 75.0, 175.0), (35, 68.0, 162.0), (80, 30.0, 120.0)]:
                d = Obj(age=age, weight_kg=w, height_cm=h, gender=g, activity_level=act, goal=goal)
                dn._compute_daily_targets(d)
                targets.append({"in": [g, act, goal, age, w, h], "out": [d.bmr_kcal, d.tdee_kcal, d.target_protein_g, d.target_lipid_g, d.target_carb_g, d.target_sodium_mg, d.target_fiber_g]})
OUT["targets"] = targets

logs = [
    Obj(nutrition_protein_g=25.0, nutrition_lipid_g=10.0, nutrition_carb_g=30.0, nutrition_sodium_mg=1500.0, nutrition_fiber_g=2.0, confidence_parse=0.95, confidence_source=0.98),
    Obj(nutrition_protein_g=8.0, nutrition_lipid_g=5.0, nutrition_carb_g=10.0, nutrition_sodium_mg=100.0, nutrition_fiber_g=0.0, confidence_parse=0.65, confidence_source=0.2),
    Obj(nutrition_protein_g=3.3333, nutrition_lipid_g=None, nutrition_carb_g=1.0, nutrition_sodium_mg=12.5, nutrition_fiber_g=0.25, confidence_parse=0.0, confidence_source=1.7),
]
dn_mod.DailyMealLog = FakeModel(lambda st: logs)
unc = dn._compute_consumed_uncertainty(Obj())
OUT["uncertainty"] = unc
daily_obj = Obj(date="2026-09-23", bmr_kcal=1762.627, tdee_kcal=2732.07185, target_protein_g=75.0, target_lipid_g=81.962,
                target_carb_g=421.093, target_sodium_mg=2300.0, target_fiber_g=38.249,
                consumed_protein_g=36.333, consumed_lipid_g=15.0, consumed_carb_g=41.0, consumed_sodium_mg=1612.5, consumed_fiber_g=2.25)
summary = dn._build_summary(daily_obj)
OUT["summary"] = summary

remaining_cases = [
    summary["remaining"],
    {"protein_g": 0.0, "lipid_g": -3.0, "carb_g": 50.0, "sodium_mg": 300.0, "fiber_g": 1.0, "carb_g_lower": 40.0, "carb_g_upper": 60.0},
    {"protein_g": 20.0, "lipid_g": 10.0, "carb_g": 0.5, "sodium_mg": 401.0, "fiber_g": 6.0},
    {},
]
dish_nuts = [
    {"protein_g": 25.0, "lipid_g": 12.0, "carb_g": 45.0, "sodium_mg": 1200.0, "fiber_g": 1.0},
    {"protein_g": 0.0, "lipid_g": 0.0, "carb_g": 0.0, "sodium_mg": 0.0, "fiber_g": 0.0},
    {"protein_g": 60.0, "lipid_g": 90.0, "carb_g": 55.0, "sodium_mg": 2500.0, "fiber_g": 20.0},
    {"protein_g": 20.0, "lipid_g": 10.0, "carb_g": 0.4, "sodium_mg": 100.0, "fiber_g": 0.0},
]
OUT["nutrition_fit"] = []
for rem in remaining_cases:
    for nut in dish_nuts:
        servings = dn._suggest_servings(remaining=rem, dish_nutrition=nut)
        OUT["nutrition_fit"].append({
            "remaining": rem, "dish": nut,
            "macro": dn._macro_match_score(remaining=rem, dish_nutrition=nut),
            "sodium": dn._sodium_penalty(remaining=rem, dish_nutrition=nut),
            "servings": servings,
            "portion": dn._validate_meal_portion(servings=servings, dish_nutrition=nut, daily_target=summary["target"]),
            "portion_2_4": dn._validate_meal_portion(servings=2.4, dish_nutrition=nut, daily_target=summary["target"]),
            "reasons": dn._remaining_reasons(remaining=rem),
        })

OUT["source_conf"] = []
for a, b, nut, q in [
    ("phở bò", "Phở bò", dish_nuts[0], 0.9),
    ("pho bo tai", "Phở bò", dish_nuts[0], 0.98),
    ("com ga", "Cơm gà xối mỡ", {"protein_g": 1.0, "lipid_g": 0.0, "carb_g": 2.0, "sodium_mg": None, "fiber_g": 0.0}, 0.8),
    ("banh mi", "Bún chả", dish_nuts[1], 0.5),
    ("", "x", dish_nuts[0], 0.9),
    ("tra sua tran chau", "trà sữa trân châu đường đen", dish_nuts[2], None),
]:
    OUT["source_conf"].append({"a": a, "b": b, "nut": nut, "q": q,
                               "sim": dn._name_similarity(a, b), "complete": dn._nutrition_completeness(nut),
                               "conf": dn._compute_source_confidence(parsed_name=a, resolved_name=b, nutrition=nut, base_quality=q)})

OUT["heuristic"] = []
for t in ["Sáng ăn 1 tô phở bò to, trưa cơm gà + trà đá và bánh flan",
          "tối mới ăn bún chả nhiều kèm nem",
          "  ", "a, b", "cơm tấm tô lớn với trứng ít",
          "hôm nay uống nước cam nhỏ"]:
    OUT["heuristic"].append({"text": t, "meals": [[m.name, m.quantity_multiplier, m.confidence_parse] for m in dn._heuristic_parse(t)]})

OUT["normalize_rows"] = [[m.name, m.quantity_multiplier, m.confidence_parse] for m in dn._normalize_parsed_rows([
    {"name": " Phở ", "quantity_multiplier": 1.5, "confidence_parse": 0.9},
    {"name": "Cơm", "quantity_multiplier": 9, "confidence": 0.4},
    {"name": "Trà", "quantity_multiplier": 0, "confidence_parse": 0},
    {"name": "Kem", "quantity_multiplier": 0.1, "confidence_parse": 1.5},
    {"name": ""}, "junk", {"name": None},
])]
OUT["json_safe"] = [[s, dn._parse_json_safely(dn._extract_json(s))] for s in [
    '{"meals":[{"name":"a"}]}', '```json\n{"a": 1}\n```', 'prefix {"a": {"b": 2}} suffix', 'nope', '', '[1,2]', '{bad json}',
    'x {"a":1} y {"b":2} z',
]]
OUT["meal_time"] = [[h, dn._infer_meal_time_from_delivery_time(dt_time(h, 30))] for h in range(24)] + [["none", dn._infer_meal_time_from_delivery_time(None)]]

# dish nutrition map + quality map
dnm_rows = [
    {"dish_id": D1, "protein": 20.0, "lipid": 5.0, "carbohydrate": 30.0, "natri": 400.0, "fiber": 2.0, "dish__serving_size": 2, "weight": 100.0, "confidence": 0.9},
    {"dish_id": D1, "protein": 3.0, "lipid": None, "carbohydrate": 12.5, "natri": 10.0, "fiber": None, "dish__serving_size": 2, "weight": None, "confidence": None},
    {"dish_id": D2, "protein": 8.0, "lipid": 4.0, "carbohydrate": None, "natri": 100.0, "fiber": 1.0, "dish__serving_size": 0, "weight": -3.0, "confidence": 0.5},
    {"dish_id": D2, "protein": 6.0, "lipid": 1.0, "carbohydrate": 22.0, "natri": 2.0, "fiber": 3.0, "dish__serving_size": 0, "weight": 30.0, "confidence": 1.4},
]
dn_mod.DishIngredient = FakeModel(lambda st: dnm_rows)
OUT["dish_nutrition_map"] = dn._get_dish_nutrition_map([D1, D2, D3])
OUT["dish_quality_map"] = dn._get_dish_quality_map([D1, D2, D3])

# recipe mapping
import ingredient.models as ing_models
ING_A, ING_B = "aaaaaaaa-0000-0000-0000-000000000001", "bbbbbbbb-0000-0000-0000-000000000002"
dn._match_usda_ingredient = lambda name: Obj(uid=ING_A) if "bo" in name else (Obj(uid=ING_B) if "pho" in name else None)
mapped, rconf = dn._normalize_and_map_recipe({"dish": "phở bò", "confidence": 0.78, "ingredients": [
    {"name": "banh pho", "weight_g": 200}, {"name": "thit bo", "weight": 100.5}, {"name": "hanh", "weight_g": 10},
    {"name": "", "weight_g": 5}, {"name": "x", "weight_g": 0}, "junk", {"name": "muoi bo", "weight_g": 1.23456}]})
OUT["recipe_mapped"] = {"mapped": mapped, "conf": rconf}
dn_mod.Ingredient = FakeModel(lambda st: [Obj(uid=ING_A, protein=26.0, lipid=15.0, carbohydrate=0.0, natri=72.0, fiber=None),
                                          Obj(uid=ING_B, protein=3.2, lipid=0.4, carbohydrate=25.1, natri=None, fiber=1.1)])
OUT["recipe_nutrition"] = dn._compute_recipe_nutrition(mapped)
OUT["recipe_conf_clamp"] = dn._normalize_and_map_recipe({"confidence": 7, "ingredients": []})[1]

# balanced recommendations (ranking math) end to end
import recommendation.services.pipeline as pipe_mod
base_items = []
import itertools
scores = [0.61, 0.55, 0.42, 0.40, 0.33, 0.1, -0.05]
for i, s in enumerate(scores):
    uid = "d%07d-0000-0000-0000-000000000000" % i
    base_items.append({"dish_uid": uid, "dish_name": "Dish %d" % i, "public_url": None if i % 2 else "https://x/%d.png" % i,
                       "price": 30000.0 + i, "avg_rating": 4.0 - i * 0.1, "score": s,
                       "reasons": ["r1-%d" % i, "r2-%d" % i, "r3-%d" % i]})


class FakePipeline:
    def recommend_for_user(self, **kw):
        OUT.setdefault("balanced_pipeline_calls", []).append(kw)
        return {"items": [dict(x) for x in base_items]}


pipe_mod.RecommendationPipelineService = FakePipeline
bn_map = {
    base_items[0]["dish_uid"]: {"protein_g": 25.0, "lipid_g": 12.0, "carb_g": 45.0, "sodium_mg": 1200.0, "fiber_g": 1.0},
    base_items[1]["dish_uid"]: {"protein_g": 60.0, "lipid_g": 90.0, "carb_g": 55.0, "sodium_mg": 2500.0, "fiber_g": 20.0},
    base_items[2]["dish_uid"]: {"protein_g": 5.0, "lipid_g": 1.0, "carb_g": 80.0, "sodium_mg": 10.0, "fiber_g": 3.0},
    base_items[4]["dish_uid"]: {"protein_g": 0.0, "lipid_g": 0.0, "carb_g": 0.0, "sodium_mg": 0.0, "fiber_g": 0.0},
    base_items[5]["dish_uid"]: {"protein_g": 30.0, "lipid_g": 30.0, "carb_g": 30.0, "sodium_mg": 3000.0, "fiber_g": 30.0},
}
bq_map = {base_items[0]["dish_uid"]: 0.9, base_items[1]["dish_uid"]: 0.3, base_items[2]["dish_uid"]: 1.0}
already_logged = [base_items[0]["dish_uid"]]
dn2 = DailyNutritionService()
dn2._get_today_daily = lambda user_id: daily_obj
dn2._sync_app_order_logs = lambda daily, order_rows=None: 0
dn2._recalculate_consumed_totals = lambda daily: None
dn2._compute_consumed_uncertainty = lambda daily: unc
dn2._get_dish_nutrition_map = lambda ids: {k: v for k, v in bn_map.items() if k in ids}
dn2._get_dish_quality_map = lambda ids: {k: bq_map.get(k, 0.9) for k in ids}
dn_mod.DailyMealLog = FakeModel(lambda st: already_logged)
OUT["balanced_limit3"] = dn2.get_balanced_recommendations(user_id=1, limit=3)
OUT["balanced_limit20"] = dn2.get_balanced_recommendations(user_id=1, limit=20)
already_logged[:] = []
OUT["balanced_limit2_nolog"] = dn2.get_balanced_recommendations(user_id=1, limit=2)
OUT["balanced_summary_used"] = summary

# ---------------------------------------------------------------------------
# 9. find_better_dish_for_issue scoring (real method, row-level fakes)
# ---------------------------------------------------------------------------
import dish.models as dish_models
import review.models as review_models
import django.db as djdb
import recommendation.services.candidates as cm
SRC = "99999999-9999-9999-9999-999999999999"
C = ["c%07d-0000-0000-0000-000000000000" % i for i in range(12)]


class DishQS(FakeQS):
    pass


def dish_handler(state):
    flt = state["filters"]
    if any("uid" in f for f in flt):
        return [Obj(uid=SRC, name="Pho bo", name_no_accent="pho bo", category="FOOD", price=10, attachment=None)]
    if any("uid__in" in f for f in flt):
        wanted = [u for f in flt for u in f.get("uid__in", [])]
        return [Obj(uid=u, name="N-" + u[:8], price=1000 + i, attachment=(Obj(public_url="https://img/" + u[:8]) if i % 2 == 0 else None))
                for i, u in enumerate(wanted)]
    return []


class DishModel:
    class _M:
        def filter(self, *a, **kw):
            return FakeQS(dish_handler).filter(**kw)

        def select_related(self, *a):
            return FakeQS(dish_handler)
    objects = _M()


class _FirstQS(FakeQS):
    def first(self):
        r = self._rows()
        return r[0] if r else None


def patched_first(self):
    r = self._rows()
    return r[0] if r else None


FakeQS.first = patched_first
review_issue_counts = [{"dish__uid": C[0], "count": 3}, {"dish__uid": C[1], "count": 1}, {"dish__uid": C[4], "count": 5}]


def review_handler2(state):
    if state["annotate"] == ("count",):
        return review_issue_counts
    return [Obj(issue="mặn", created_at=NOW)]


class ReviewModel:
    objects = FakeManager(review_handler2)


orig_dish, orig_review = dish_models.Dish, review_models.Review
dish_models.Dish = DishModel
review_models.Review = ReviewModel


class VCur:
    def __enter__(self): return self
    def __exit__(self, *a): return False
    def execute(self, *a): pass
    def fetchone(self): return ("[1,2,3,0.4,5]",)


orig_conn = djdb.connection
djdb.connection = Obj(cursor=lambda: VCur())
cm.CandidateGenerator._vector_source_ids = lambda self, **kw: [SRC] + C
rs2 = RecommendationService()
rs2.review_orm = Obj(get_bulk_dish_rating_stats=lambda ids: [
    {"dish_uid": C[0], "avg_rating": 4.8, "total_reviews": 10},
    {"dish_uid": C[1], "avg_rating": 3.2, "total_reviews": 250},
    {"dish_uid": C[2], "avg_rating": 4.1, "total_reviews": 3},
    {"dish_uid": C[3], "avg_rating": 2.0, "total_reviews": 0},
    {"dish_uid": C[4], "avg_rating": 5.0, "total_reviews": 5},
    {"dish_uid": C[5], "avg_rating": 4.4, "total_reviews": 77},
])
res = rs2.find_better_dish_for_issue(user_id=1, dish_uid=SRC, limit=5)
OUT["better_for_issue"] = res
djdb.connection = orig_conn
dish_models.Dish, review_models.Review = orig_dish, orig_review

# ---------------------------------------------------------------------------
# 10. python round parity cases
# ---------------------------------------------------------------------------
OUT["pyround"] = [[x, n, round(x, n)] for x, n in [(2.675, 2), (0.0005, 3), (0.0015, 3), (1.0000005, 6), (2.5, 0),
                                                    (-0.0004999, 3), (123.4565, 3), (0.1234565, 6), (1e-7, 6), (5e-7, 6)]]
OUT["exp_samples"] = [[x, math.exp(x)] for x in [-0.03 * 1.0833333333333333, -0.9, -6.0, -0.0000347, -4.0000001]]


def _iso(v):
    return v.isoformat() if hasattr(v, "isoformat") else v


def _rows(rows):
    return [{k: _iso(v) for k, v in r.items()} for r in rows]


OUT["inputs"] = {
    "dishes": [{"uid": d.uid, "avg_rating": d.avg_rating, "created_at": d.created_at.isoformat()} for d in dishes],
    "order_rows": _rows(order_rows),
    "popularity_rows": popularity_rows,
    "ingredient_total_rows": ingredient_total_rows,
    "ingredient_match_rows": ingredient_match_rows,
    "favorite_rows": _rows(favorite_rows),
    "issue_rows": issue_rows,
    "nutrition_rows": nutrition_rows,
    "coverage_rows": coverage_rows,
    "mmr_items": mmr_items,
    "issue_rows_user": _rows(issue_rows_user),
    "vi_rows": vi_rows,
    "hist_rows": _rows(hist_rows),
    "hist_vectors": {D1: [21.5, 5.25, 31.0, 0.401, 1.9], D2: [6.0, 1.0, 22.0, 0.002, 3.0]},
    "logs": [dict(l.__dict__) for l in logs],
    "daily_obj": dict(daily_obj.__dict__),
    "dnm_rows": dnm_rows,
    "base_items": base_items,
    "bn_map": bn_map,
    "bq_map": bq_map,
    "better_candidates": [SRC] + C,
    "better_issue_counts": review_issue_counts,
    "better_rating_stats": rs2.review_orm.get_bulk_dish_rating_stats([]),
    "recipe_ingredients": {ING_A: {"protein": 26.0, "lipid": 15.0, "carbohydrate": 0.0, "natri": 72.0, "fiber": None},
                           ING_B: {"protein": 3.2, "lipid": 0.4, "carbohydrate": 25.1, "natri": None, "fiber": 1.1}},
}
OUT["_generated_by"] = "src/test/resources/recommendation/gen_vectors.py run in backend/venv against the real Django code"
print(json.dumps(OUT, ensure_ascii=False, default=str))
