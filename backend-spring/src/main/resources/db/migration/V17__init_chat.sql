-- Mirrors ../../backend/chat/models.py + migrations/0001_initial.py (Conversation, Message).
-- unique(customer, chef) = Django's unique_together; FK cascade as Django (all CASCADE).
CREATE TABLE chat_conversation (
    id          BIGSERIAL PRIMARY KEY,
    customer_id BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    chef_id     BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_chat_conversation_customer_chef UNIQUE (customer_id, chef_id)
);
CREATE INDEX idx_chat_conversation_chef ON chat_conversation (chef_id);

CREATE TABLE chat_message (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES chat_conversation (id) ON DELETE CASCADE,
    sender_id       BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    text            TEXT NOT NULL,
    is_read         BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_chat_message_conversation_created ON chat_message (conversation_id, created_at);
CREATE INDEX idx_chat_message_sender ON chat_message (sender_id);
