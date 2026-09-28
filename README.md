# SmartFood Delivery

Nền tảng đặt món ăn + gợi ý dinh dưỡng bằng AI: backend Spring Boot, 2 app frontend (admin web + mobile khách hàng), và 1 service AI phân tích review.

## Kiến trúc

```
FE-admin (React/Vite, :5173) ──┐
Fe-user  (Flutter)         ────┼──► backend-spring (Spring Boot, :8000) ──► PostgreSQL + Redis
                                │              │
                                │              └──► AI-model-prod (FastAPI, :8001)
                                │
                                └── (không gọi trực tiếp AI-model-prod)
```

## Cấu trúc thư mục

| Thư mục | Vai trò |
|---|---|
| [`backend-spring/`](backend-spring/) | Backend API (Spring Boot, Java 21), port 8000 |
| [`FE-admin/`](FE-admin/) | Trang quản trị (React + Vite) |
| [`Fe-user/`](Fe-user/) | App khách hàng (Flutter) |
| [`AI-model-prod/`](AI-model-prod/) | Service phân tích review bằng PhoBERT (FastAPI), port 8001 |
| `compose/` | docker-compose để deploy production |
| `terraform/` | Hạ tầng AWS: VPC, ALB (blue/green), Auto Scaling Group, IAM, Secrets Manager |
| `scripts/` | Script deploy production |
| `k6-test/` | Load test (k6) |
| `env/.env.example` | Mẫu biến môi trường |

## Chạy dev

### 1. Backend

```bash
cd backend-spring
docker compose -f docker-compose.dev.yml up -d   # Postgres :15432, Redis :16379
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

- API: http://localhost:8000
- Swagger UI: http://localhost:8000/docs
- Health check: http://localhost:8000/actuator/health

Chi tiết đầy đủ (seed tài khoản dev, biến môi trường, troubleshooting): [`backend-spring/README.md`](backend-spring/README.md) · [`backend-spring/DEV.md`](backend-spring/DEV.md)

### 2. FE-admin (trang quản trị)

```bash
cd FE-admin
npm install
npm run dev
```
→ http://localhost:5173 (proxy `/api` sang backend port 8000)

### 3. Fe-user (app khách hàng, Flutter)

```bash
cd Fe-user
flutter pub get
flutter run
```

### 4. AI-model-prod (tùy chọn)

Không bắt buộc để chạy dev — nếu tắt, tính năng phân tích review của backend tự dùng giá trị mặc định.

```bash
cd AI-model-prod
pip install -r requirements.txt
python api.py
```
→ http://localhost:8001

## Deploy production

- `terraform/` provision hạ tầng AWS: VPC + ALB (traffic 95% production / 5% canary), Auto Scaling Group EC2 chạy Docker Compose, IAM role cho GitHub Actions (OIDC), Secrets Manager cho biến môi trường backend, S3 lưu Terraform state.
- `backend-spring/Dockerfile` build image backend, `compose/docker-compose.yml` định nghĩa 3 service chạy trên mỗi EC2: `backend`, `ai-service`, `frontend` (+ `redis`).
- `scripts/deploy-production.sh` tải `compose/docker-compose.yml` từ S3, lấy secrets từ Secrets Manager, và chạy `docker compose up -d` trên instance — hỗ trợ cả deploy thường và canary.

## Testing

```bash
cd backend-spring && ./mvnw test
cd FE-admin && npm run lint && npm run build
cd Fe-user && flutter analyze && flutter test
```

CI chạy cả 3 lệnh trên ở mỗi push/PR — xem [`.github/workflows/ci.yml`](.github/workflows/ci.yml).

---
**Cập nhật lần cuối**: 2026-09-28
