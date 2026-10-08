# Internal Wallet

Ứng dụng ví điện tử nội bộ gồm giao diện web, REST API Java Spring Boot và PostgreSQL.

Các chức năng chính: đăng ký/đăng nhập, xem ví và lịch sử, chuyển tiền, xuất sao kê CSV/PDF, xem trước và xác nhận nhập chi tiêu từ CSV, xem thống kê, cấp tiền và đối soát số dư bằng tài khoản ADMIN.

## Khởi chạy bằng Docker Compose

Cần Docker Desktop hoặc Docker Engine có Compose. Trong thư mục gốc dự án:

```powershell
Copy-Item .env.example .env
notepad .env
docker compose up --build -d
```

Điền `DB_PASSWORD` trong `.env` trước khi chạy. Để tạo ADMIN ban đầu, điền cả `APP_BOOTSTRAP_ADMIN_EMAIL` và `APP_BOOTSTRAP_ADMIN_PASSWORD` (ít nhất 10 ký tự). Tài khoản này chỉ được tạo nếu email chưa tồn tại. Không commit `.env`.

Mở [http://localhost:8080](http://localhost:8080) để dùng giao diện web. API ở `http://localhost:8080/api/v1`. Nếu cổng 8080 đã được sử dụng, đổi `API_PORT` trong `.env` rồi mở URL theo cổng mới. Compose chỉ công bố API trên máy đang chạy Docker.

```powershell
docker compose ps
docker compose logs -f api
docker compose down
```

`docker compose down` giữ lại dữ liệu PostgreSQL. Chỉ dùng `docker compose down -v` khi muốn xóa dữ liệu trong volume. Sau khi PostgreSQL đã khởi tạo, đổi `DB_PASSWORD` trong `.env` không tự đổi mật khẩu của tài khoản trong database.

## Kiểm thử và tài liệu

Với JDK 25 và Maven 3.9+, chạy unit tests bằng `mvn test`. Để chạy `WalletApiIT` với PostgreSQL tạm qua Testcontainers, bật Docker rồi chạy `mvn '-Dtest=WalletApiIT' test`. Integration test xóa dữ liệu trước mỗi ca kiểm thử, vì vậy chỉ dùng database dành riêng cho test.

GitHub Actions chạy unit tests, integration tests với PostgreSQL tạm và build Docker image trên mỗi lần push hoặc tạo/cập nhật pull request.

- [Hướng dẫn sử dụng giao diện web](docs/huong-dan-su-dung.md)
- [API](docs/api.md)
- [Kiến trúc](docs/architecture.md)
- [Design patterns](docs/patterns.md)
- [Vận hành, sao lưu và khôi phục](docs/operations.md)
- [Đo hiệu năng thống kê và sao kê](docs/performance.md)
- [Lộ trình nhiều loại tài khoản và quy tắc phí](docs/account-types.md)
- [Tệp mẫu nhập chi tiêu CSV](samples/chi-tieu-mau.csv)
