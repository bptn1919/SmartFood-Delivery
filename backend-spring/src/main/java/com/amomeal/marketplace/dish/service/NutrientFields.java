package com.amomeal.marketplace.dish.service;

import com.amomeal.marketplace.dish.entity.DishIngredient;
import com.amomeal.marketplace.ingredient.entity.Ingredient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Field-name-keyed access to the 19 nutrition columns, replacing Python's
 * {@code getattr(obj, field, None)} / {@code setattr(obj, field, value)} which
 * the dish nutrition pipeline leans on heavily.
 *
 * <p>{@link #DISH_NUTRIENT_FIELDS} is a verbatim port of
 * ../../backend/ingredient/constants.py::DISH_NUTRIENT_FIELDS — the order
 * matters, because several severity/confidence loops iterate it and the
 * resulting {@code warnings} list is returned to the client in iteration order.
 *
 * <p>Using explicit accessor maps rather than reflection keeps this
 * compile-checked (a renamed entity field breaks the build instead of silently
 * turning into a "field missing -&gt; None" no-op at runtime).
 */
public final class NutrientFields {

    private NutrientFields() {
    }

    /** Verbatim port of ingredient/constants.py::DISH_NUTRIENT_FIELDS (order-significant). */
    public static final List<String> DISH_NUTRIENT_FIELDS = List.of(
            "energy",
            "protein",
            "lipid",
            "carbohydrate",
            "fiber",
            "natri",
            "kali",
            "cholesterol",
            "retinol",
            "caroten",
            "vitamin_b_1",
            "vitamin_b_2",
            "vitamin_pp",
            "vitamin_c",
            "calcium",
            "phosphorus",
            "fe",
            "mg",
            "zn");

    private static final Map<String, Function<DishIngredient, Double>> DI_GETTERS = new LinkedHashMap<>();
    private static final Map<String, BiConsumer<DishIngredient, Double>> DI_SETTERS = new LinkedHashMap<>();
    private static final Map<String, Function<Ingredient, Double>> ING_GETTERS = new LinkedHashMap<>();

    static {
        DI_GETTERS.put("energy", DishIngredient::getEnergy);
        DI_GETTERS.put("protein", DishIngredient::getProtein);
        DI_GETTERS.put("lipid", DishIngredient::getLipid);
        DI_GETTERS.put("carbohydrate", DishIngredient::getCarbohydrate);
        DI_GETTERS.put("fiber", DishIngredient::getFiber);
        DI_GETTERS.put("natri", DishIngredient::getNatri);
        DI_GETTERS.put("kali", DishIngredient::getKali);
        DI_GETTERS.put("cholesterol", DishIngredient::getCholesterol);
        DI_GETTERS.put("retinol", DishIngredient::getRetinol);
        DI_GETTERS.put("caroten", DishIngredient::getCaroten);
        DI_GETTERS.put("vitamin_b_1", DishIngredient::getVitaminB1);
        DI_GETTERS.put("vitamin_b_2", DishIngredient::getVitaminB2);
        DI_GETTERS.put("vitamin_pp", DishIngredient::getVitaminPp);
        DI_GETTERS.put("vitamin_c", DishIngredient::getVitaminC);
        DI_GETTERS.put("calcium", DishIngredient::getCalcium);
        DI_GETTERS.put("phosphorus", DishIngredient::getPhosphorus);
        DI_GETTERS.put("fe", DishIngredient::getFe);
        DI_GETTERS.put("mg", DishIngredient::getMg);
        DI_GETTERS.put("zn", DishIngredient::getZn);

        DI_SETTERS.put("energy", DishIngredient::setEnergy);
        DI_SETTERS.put("protein", DishIngredient::setProtein);
        DI_SETTERS.put("lipid", DishIngredient::setLipid);
        DI_SETTERS.put("carbohydrate", DishIngredient::setCarbohydrate);
        DI_SETTERS.put("fiber", DishIngredient::setFiber);
        DI_SETTERS.put("natri", DishIngredient::setNatri);
        DI_SETTERS.put("kali", DishIngredient::setKali);
        DI_SETTERS.put("cholesterol", DishIngredient::setCholesterol);
        DI_SETTERS.put("retinol", DishIngredient::setRetinol);
        DI_SETTERS.put("caroten", DishIngredient::setCaroten);
        DI_SETTERS.put("vitamin_b_1", DishIngredient::setVitaminB1);
        DI_SETTERS.put("vitamin_b_2", DishIngredient::setVitaminB2);
        DI_SETTERS.put("vitamin_pp", DishIngredient::setVitaminPp);
        DI_SETTERS.put("vitamin_c", DishIngredient::setVitaminC);
        DI_SETTERS.put("calcium", DishIngredient::setCalcium);
        DI_SETTERS.put("phosphorus", DishIngredient::setPhosphorus);
        DI_SETTERS.put("fe", DishIngredient::setFe);
        DI_SETTERS.put("mg", DishIngredient::setMg);
        DI_SETTERS.put("zn", DishIngredient::setZn);

        ING_GETTERS.put("energy", Ingredient::getEnergy);
        ING_GETTERS.put("protein", Ingredient::getProtein);
        ING_GETTERS.put("lipid", Ingredient::getLipid);
        ING_GETTERS.put("carbohydrate", Ingredient::getCarbohydrate);
        ING_GETTERS.put("fiber", Ingredient::getFiber);
        ING_GETTERS.put("natri", Ingredient::getNatri);
        ING_GETTERS.put("kali", Ingredient::getKali);
        ING_GETTERS.put("cholesterol", Ingredient::getCholesterol);
        ING_GETTERS.put("retinol", Ingredient::getRetinol);
        ING_GETTERS.put("caroten", Ingredient::getCaroten);
        ING_GETTERS.put("vitamin_b_1", Ingredient::getVitaminB1);
        ING_GETTERS.put("vitamin_b_2", Ingredient::getVitaminB2);
        ING_GETTERS.put("vitamin_pp", Ingredient::getVitaminPp);
        ING_GETTERS.put("vitamin_c", Ingredient::getVitaminC);
        ING_GETTERS.put("calcium", Ingredient::getCalcium);
        ING_GETTERS.put("phosphorus", Ingredient::getPhosphorus);
        ING_GETTERS.put("fe", Ingredient::getFe);
        ING_GETTERS.put("mg", Ingredient::getMg);
        ING_GETTERS.put("zn", Ingredient::getZn);
    }

    public static Double get(DishIngredient dishIngredient, String field) {
        Function<DishIngredient, Double> getter = DI_GETTERS.get(field);
        return getter == null ? null : getter.apply(dishIngredient);
    }

    public static void set(DishIngredient dishIngredient, String field, Double value) {
        BiConsumer<DishIngredient, Double> setter = DI_SETTERS.get(field);
        if (setter != null) {
            setter.accept(dishIngredient, value);
        }
    }

    /** Mirrors {@code getattr(ingredient, field, None)} against an `ingredient` row. */
    public static Double get(Ingredient ingredient, String field) {
        Function<Ingredient, Double> getter = ING_GETTERS.get(field);
        return getter == null ? null : getter.apply(ingredient);
    }

    /** Applies a whole field-keyed nutrient map onto a DishIngredient (Django's {@code **nutrient_values}). */
    public static void applyAll(DishIngredient dishIngredient, Map<String, Double> values) {
        if (values == null) {
            return;
        }
        for (Map.Entry<String, Double> entry : values.entrySet()) {
            set(dishIngredient, entry.getKey(), entry.getValue());
        }
    }
}
