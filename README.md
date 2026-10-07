# Internal Wallet — đồ án Design Patterns

Ứng dụng ví điện tử nội bộ bằng Java. Trạng thái hiện tại: **mốc 3 / 4** — Spring Boot API, PostgreSQL, xác thực, ví, chuyển tiền, cấp số dư thử nghiệm, xuất sao kê CSV/PDF, nhập CSV chi tiêu và thống kê. JavaFX thuộc mốc sau. Hợp đồng cho nhóm desktop ở [docs/api.md](docs/api.md).

## Chạy server trên Windows

Yêu cầu JDK 25, Maven 3.9+ và PostgreSQL 17/18. Tạo database UTF-8 tên `internal_wallet` và role `wallet_app` có quyền tạo bảng trong schema `public`. Flyway tự chạy migration khi server khởi động. Mở PowerShell tại thư mục dự án, đặt thông tin kết nối trong **biến môi trường của phiên terminal** (không commit mật khẩu):

```powershell
java -version
$env:DB_URL = 'jdbc:postgresql://localhost:5432/internal_wallet'
$env:DB_USER = 'wallet_app'
$env:DB_PASSWORD = Read-Host 'Database password'
$env:APP_BOOTSTRAP_ADMIN_EMAIL = 'admin@example.test'
$env:APP_BOOTSTRAP_ADMIN_PASSWORD = Read-Host 'Initial admin password'
mvn spring-boot:run
```

Admin demo được tạo một lần nếu cả hai biến bootstrap hợp lệ và email chưa tồn tại; không có mật khẩu mặc định. Password cần ít nhất 10 ký tự. API ở `http://localhost:8080/api/v1`. Không dùng HTTP qua mạng ngoài máy cá nhân; khi triển khai nhiều máy, cấu hình HTTPS ở reverse proxy.

Nếu `mvn` chưa ở `PATH`, dùng Maven đã cài trên máy. Migration nằm tại `src/main/resources/db/migration/V1__init.sql` và `V2__expense_import_dedup.sql`.

## Demo ba pattern qua API

Đăng nhập lấy `accessToken`, đặt `$token` thành giá trị đó, rồi gọi các API sau bằng PowerShell. Ví cần có chuyển tiền để sao kê có dòng; hai tệp CSV mẫu chỉ tạo khoản chi thống kê, không cộng/trừ ví.

```powershell
$headers = @{ Authorization = "Bearer $token" }
Invoke-WebRequest 'http://localhost:8080/api/v1/statements?from=2026-01-01&to=2026-12-31&format=csv' -Headers $headers -OutFile statement.csv
Invoke-WebRequest 'http://localhost:8080/api/v1/statements?from=2026-01-01&to=2026-12-31&format=pdf' -Headers $headers -OutFile statement.pdf
$keyA = [guid]::NewGuid().ToString()
curl.exe -H "Authorization: Bearer $token" -F "requestKey=$keyA" -F "file=@samples/expenses-a.csv;type=text/csv" 'http://localhost:8080/api/v1/expense-imports?format=SAMPLE_A'
$keyB = [guid]::NewGuid().ToString()
curl.exe -H "Authorization: Bearer $token" -F "requestKey=$keyB" -F "file=@samples/expenses-b.csv;type=text/csv" 'http://localhost:8080/api/v1/expense-imports?format=SAMPLE_B'
Invoke-RestMethod 'http://localhost:8080/api/v1/expense-stats?from=2026-01-01&to=2026-12-31&groupBy=category' -Headers $headers
Invoke-RestMethod 'http://localhost:8080/api/v1/expense-stats?from=2026-01-01&to=2026-12-31&groupBy=month' -Headers $headers
```

Luồng lớp và sơ đồ từng pattern ở [docs/patterns.md](docs/patterns.md). Tất cả tiền là số nguyên đồng VND, không làm tròn số lẻ.

## Kiểm thử

```powershell
mvn test
```

Lệnh trên chạy unit tests. Integration tests `WalletApiIT` dùng Testcontainers PostgreSQL khi Docker Desktop đang chạy:

```powershell
mvn '-Dtest=WalletApiIT' test
```

Nếu đã có PostgreSQL thử nghiệm riêng, có thể dùng nó thay Testcontainers. Database **bị TRUNCATE** trước từng test, vì vậy chỉ trỏ đến database trống dành riêng cho test:

```powershell
mvn '-Dtest=WalletApiIT' '-Dit.db.url=jdbc:postgresql://localhost:55432/wallet_it' '-Dit.db.user=wallet_test' '-Dit.db.password=...' test
```

`wallet_it` trong ví dụ là database thử nghiệm, không dùng chung dữ liệu thật. Khi chạy bằng Testcontainers cần Docker daemon hoạt động; khi dùng PostgreSQL riêng thì không cần Docker.

## Kế hoạch mốc

1. Nền tảng: quy ước tiền VND nguyên đồng, quy tắc chuyển tiền, migration, API và pattern docs; `mvn test` xanh.
2. Spring Boot + PostgreSQL: xác thực, ví, chuyển tiền transaction, quản trị và kiểm thử tích hợp — đã triển khai.
3. Sao kê CSV/PDF, nhập CSV hai định dạng và thống kê theo danh mục/tháng — đã triển khai.
4. JavaFX FXML/CSS: đăng nhập, dashboard, chuyển tiền, lịch sử, sao kê và hướng dẫn chạy toàn luồng.

Không commit `.env`, mật khẩu, token hoặc thông tin kết nối thật. Hai CSV trong `samples/` là dữ liệu chi tiêu giả lập và không sửa số dư ví.
