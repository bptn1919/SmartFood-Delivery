package com.amomeal.marketplace.recommendation.service;

import com.amomeal.marketplace.dish.entity.DishIngredient;
import com.amomeal.marketplace.order.entity.Order;
import com.amomeal.marketplace.order.entity.OrderStatus;
import com.amomeal.marketplace.recommendation.config.RecommendationProperties;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManagerFactory;
import lombok.RequiredArgsConstructor;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.PostDeleteEvent;
import org.hibernate.event.spi.PostDeleteEventListener;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Django's model-save signals that feed the vector index, attached to {@code order}'s and
 * {@code dish}'s entities through Hibernate event listeners (no edit to those modules):
 *
 * <ul>
 *   <li>{@code order/signals.py::refresh_user_vector_on_order_completed} — pre_save caches the
 *       previous status, post_save fires when an Order (inserted or updated) is COMPLETED and was
 *       not before; {@code transaction.on_commit → VectorIndexService.refresh_user_vector(owner,
 *       history_decay_lambda)}.</li>
 *   <li>{@code dish/signals.py::refresh_dish_vector_on_save/_on_delete} — any DishIngredient
 *       save/delete → on_commit {@code refresh_dish_vectors_for_dishes([dish_id])}.</li>
 * </ul>
 * Like Django signals, bulk JPQL/SQL updates (Django {@code .update()}) do not fire these.
 */
@Component
@RequiredArgsConstructor
public class RecommendationEntityListeners implements PostInsertEventListener, PostUpdateEventListener,
        PostDeleteEventListener {

    private final EntityManagerFactory entityManagerFactory;
    private final VectorIndexService vectorIndexService;
    private final PostCommitRunner postCommit;
    private final RecommendationProperties config;

    @PostConstruct
    void register() {
        SessionFactoryImplementor sf = entityManagerFactory.unwrap(SessionFactoryImplementor.class);
        EventListenerRegistry registry = sf.getServiceRegistry().getService(EventListenerRegistry.class);
        registry.appendListeners(EventType.POST_INSERT, this);
        registry.appendListeners(EventType.POST_UPDATE, this);
        registry.appendListeners(EventType.POST_DELETE, this);
    }

    @Override
    public void onPostInsert(PostInsertEvent event) {
        if (event.getEntity() instanceof Order order) {
            onOrderSaved(order, null);
        } else if (event.getEntity() instanceof DishIngredient di) {
            onDishIngredientChanged(di);
        }
    }

    @Override
    public void onPostUpdate(PostUpdateEvent event) {
        if (event.getEntity() instanceof Order order) {
            Object previous = null;
            Object[] oldState = event.getOldState();
            if (oldState != null) {
                String[] names = event.getPersister().getPropertyNames();
                for (int i = 0; i < names.length; i++) {
                    if ("status".equals(names[i])) {
                        previous = oldState[i];
                        break;
                    }
                }
            }
            onOrderSaved(order, previous);
        } else if (event.getEntity() instanceof DishIngredient di) {
            onDishIngredientChanged(di);
        }
    }

    @Override
    public void onPostDelete(PostDeleteEvent event) {
        if (event.getEntity() instanceof DishIngredient di) {
            onDishIngredientChanged(di);
        }
    }

    @Override
    public boolean requiresPostCommitHandling(EntityPersister persister) {
        return false;
    }

    private void onOrderSaved(Order order, Object previousStatus) {
        if (order.getOwner() == null || order.getOwner().getId() == null) {
            return;
        }
        boolean isCompleted = order.getStatus() == OrderStatus.COMPLETED;
        boolean wasCompleted = previousStatus == OrderStatus.COMPLETED;
        if (!isCompleted || wasCompleted) {
            return;
        }
        long ownerId = order.getOwner().getId();
        double lambda = config.getHistoryDecayLambda();
        postCommit.afterCommitNonTransactional(() -> vectorIndexService.refreshUserVector(ownerId, lambda, false));
    }

    private void onDishIngredientChanged(DishIngredient di) {
        if (di.getDish() == null || di.getDish().getUid() == null) {
            return;
        }
        String dishId = di.getDish().getUid().toString();
        postCommit.afterCommitNonTransactional(() -> vectorIndexService.refreshDishVectorsForDishes(List.of(dishId), null));
    }
}
