# Backend Spring

Dự án backend Spring Boot cho hệ thống food delivery, tương đương phần Django marketplace đã có sẵn trong thư mục `backend/`.

## Yêu cầu

- Java 21
- Maven Wrapper có sẵn (`./mvnw`)
- Docker + Docker Compose
- Git Bash / PowerShell / bash trên máy local

## 1. Khởi động cơ sở dữ liệu local

Project dùng PostgreSQL và Redis cho môi trường dev. Port được map khác với mặc định để tránh xung đột với máy cài sẵn Postgres/Redis.

```bash
docker compose -f docker-compose.dev.yml up -d
```

Dừng dịch vụ:

```bash
docker compose -f docker-compose.dev.yml down
```

Thông tin mặc định:

- PostgreSQL: `localhost:15432`
- Redis: `localhost:16379`
- Database: `amomeal`
- User: `postgres`
- Password: `postgres`

## 2. Khởi động backend Spring

Vào thư mục `backend-spring`:

```bash
cd backend-spring
```

Thiết lập biến môi trường và chạy ứng dụng:

```bash
export ATTACHMENT_STORAGE_BACKEND=local \
       CORS_ALLOWED_ORIGINS=http://localhost:5173 \
       DB_URL=jdbc:postgresql://localhost:15432/amomeal \
       REDIS_HOST=127.0.0.1 \
       REDIS_PORT=16379

./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Nếu dùng PowerShell:

```powershell
$env:ATTACHMENT_STORAGE_BACKEND = "local"
$env:CORS_ALLOWED_ORIGINS = "http://localhost:5173"
$env:DB_URL = "jdbc:postgresql://localhost:15432/amomeal"
$env:REDIS_HOST = "127.0.0.1"
$env:REDIS_PORT = "16379"

./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Ứng dụng chạy mặc định trên port `8000`.

## 3. Kiểm tra ứng dụng

Sau khi khởi động thành công, các endpoint quan trọng:

- API: `http://localhost:8000`
- Swagger UI: `http://localhost:8000/docs`
- OpenAPI JSON: `http://localhost:8000/openapi.json`
- Health check: `http://localhost:8000/actuator/health`

## 4. Seed tài khoản dev

Backend phải đang chạy trước khi seed tài khoản:

```bash
bash scripts/dev-seed.sh
```

Tài khoản mặc định:

- `dev_admin@amomeal.test` / password `Dev@12345`
- `dev_chef@amomeal.test` / password `Dev@12345`
- `dev_customer@amomeal.test` / password `Dev@12345`

Script sẽ:

- đăng ký 3 user
- gán quyền admin/chef/customer
- chuyển tài khoản chef thành chef thật

## 5. Chạy frontend admin (tùy chọn)

Để test với giao diện admin:

```bash
cd ../FE-admin
npm install
VITE_API_BASE_URL=http://localhost:8000 npm run dev
```

Frontend chạy mặc định trên `http://localhost:5173`.

## 6. Lưu ý quan trọng

- Chế độ dev sẽ log email OTP thay vì gửi qua SMTP. Tìm trong console từ khóa `DEV-MAIL`.
- `ATTACHMENT_STORAGE_BACKEND=local` sẽ lưu file upload vào folder `backend-spring/media/`.
- Nếu không thiết lập các biến môi trường phía ngoài như `PAYOS_*`, `GEMINI_API_KEY`, `EMAIL_*`, `S3_*`, ứng dụng vẫn có thể chạy ở chế độ dev nhưng một số chức năng nâng cao có thể bị giới hạn.
- Port mặc định 5432/6379 có thể đang được dùng trên máy local nên project dùng `15432`/`16379` cho dev docker.

## 7. Các biến môi trường quan trọng

Một số biến thường dùng trong dev:

```bash
export DB_URL=jdbc:postgresql://localhost:15432/amomeal
export DB_USERNAME=postgres
export DB_PASSWORD=postgres
export REDIS_HOST=127.0.0.1
export REDIS_PORT=16379
export CORS_ALLOWED_ORIGINS=http://localhost:5173
export ATTACHMENT_STORAGE_BACKEND=local
export SERVER_PORT=8000
```

## 8. Troubleshooting

### Không kết nối được PostgreSQL

Kiểm tra container đang chạy:

```bash
docker ps
```

Nếu chưa chạy:

```bash
docker compose -f docker-compose.dev.yml up -d
```

### App không khởi động do port bị chiếm

Kiểm tra `8000` hoặc `15432`/`16379` đã được sử dụng chưa. Nếu cần, đổi biến môi trường tương ứng hoặc thay port local.

### Không đăng nhập được

- Đảm bảo backend đang chạy
- Chạy lại `bash scripts/dev-seed.sh`
- Kiểm tra console có log `DEV-MAIL` nếu cần OTP hoặc reset mật khẩu

## 9. Môi trường dev nâng cao

Một số tính năng như thanh toán PayOS, Gemini AI, S3 storage, email thật không bắt buộc để chạy dev cơ bản nhưng cần nếu muốn dùng đầy đủ chức năng sản phẩm.

---

Nếu muốn, tôi có thể tiếp tục viết thêm:

- README cho cả project gốc + frontend + backend
- hướng dẫn chạy bằng Docker Compose toàn bộ stack
- mô tả các API chính của hệ thống
