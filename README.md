# Internal Wallet — đồ án Design Patterns

Ứng dụng ví điện tử nội bộ bằng Java. **Mốc 4 / 4** gồm server Spring Boot/PostgreSQL, giao diện web tại `http://localhost:8080` và ứng dụng desktop JavaFX ở thư mục `desktop/`. Cả hai giao diện dùng REST API theo [docs/api.md](docs/api.md), không kết nối trực tiếp database. Token đăng nhập chỉ nằm trong bộ nhớ tiến trình.

## Khởi chạy bằng Docker Compose

Yêu cầu Docker Desktop (hoặc Docker Engine) với Compose. Compose chạy giao diện web, API và PostgreSQL; ứng dụng JavaFX vẫn có thể chạy trực tiếp trên máy. Không cần cài JDK, Maven hay PostgreSQL để dùng giao diện web bằng Docker.

Trong bản web, Spring Boot phục vụ giao diện và REST API, PostgreSQL lưu ví/giao dịch, Flyway tạo và nâng cấp bảng. Các thư viện mã hóa mật khẩu, CSV và PDF phục vụ những tính năng tương ứng. Maven/JDK chỉ dùng trong bước build image; JavaFX trong `desktop/` là ứng dụng riêng và không chạy trong Compose. Testcontainers chỉ dùng khi chạy kiểm thử tích hợp, không nằm trong ứng dụng đang chạy.

Mở PowerShell ở thư mục gốc dự án:

```powershell
Copy-Item .env.example .env
notepad .env
docker compose up --build -d
docker compose ps
```

Trong `.env`, **đổi `DB_PASSWORD`** trước khi chạy. Nếu cần tài khoản ADMIN ban đầu, điền cả `APP_BOOTSTRAP_ADMIN_EMAIL` và `APP_BOOTSTRAP_ADMIN_PASSWORD` (ít nhất 10 ký tự). ADMIN chỉ được tạo khi email chưa tồn tại; thay đổi mật khẩu trong `.env` sau đó không đổi mật khẩu tài khoản đã tạo. Không commit `.env`.

Mở **`http://localhost:8080`** trên trình duyệt để dùng ứng dụng. API chạy tại `http://localhost:8080/api/v1`. Nếu cổng 8080 đang được sử dụng, đổi `API_PORT` trong `.env` (ví dụ `18080`), mở `http://localhost:18080` và đặt `WALLET_API_URL` của desktop thành `http://localhost:18080/api/v1`. Compose chỉ mở cổng trên loopback của máy chủ; PostgreSQL chỉ truy cập được trong mạng Compose. Flyway tự áp dụng migration khi API khởi động. Kiểm tra log nếu ứng dụng chưa sẵn sàng:

```powershell
docker compose logs -f api
```

Chạy JavaFX theo mục bên dưới với JDK 25 và Maven 3.9+ trên máy. URL mặc định của desktop đã trỏ đến API này. Dừng dịch vụ bằng `docker compose down`; dữ liệu PostgreSQL vẫn được giữ trong Docker volume. Chỉ khi muốn **xóa toàn bộ dữ liệu ví thử nghiệm**, chạy `docker compose down -v`.

Để build lại sau khi sửa mã server: `docker compose up --build -d`. Nếu đổi `DB_PASSWORD` sau lần khởi tạo PostgreSQL đầu tiên, phải đổi mật khẩu role trong database tương ứng; biến `POSTGRES_PASSWORD` chỉ có hiệu lực khi volume còn trống.

Nếu lần khởi động trước bị lỗi do cổng host đã được sử dụng và API liên tục restart với `UnknownHostException: db`, tạo lại riêng container API để nối lại mạng Compose (không xóa dữ liệu PostgreSQL):

```powershell
docker compose up -d --force-recreate api
docker compose ps
```

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

Nếu `mvn` chưa ở `PATH`, dùng Maven đã cài trên máy. Migration nằm tại `src/main/resources/db/migration/` (V1–V3); V3 bổ sung khóa chống gửi lặp cho khoản cấp tiền ADMIN.

## Chạy desktop trên Windows

Sau khi server chạy, mở PowerShell thứ hai tại thư mục dự án. Yêu cầu JDK 25 và Maven 3.9+. URL API mặc định là `http://localhost:8080/api/v1`; có thể đổi qua biến môi trường hoặc ngay trên màn hình đăng nhập:

```powershell
$env:WALLET_API_URL = 'http://localhost:8080/api/v1'
mvn -f desktop/pom.xml javafx:run
```

Desktop chỉ chấp nhận `http://` với host đúng `localhost` hoặc `127.0.0.1`. URL server khác phải dùng `https://`; URL chứa thông tin đăng nhập, query hoặc fragment bị từ chối.

Đăng ký tài khoản USER trên giao diện, hoặc đăng nhập bằng tài khoản đã có. Màn hình cấp số dư chỉ xuất hiện với tài khoản ADMIN được bootstrap từ server. Dùng `samples/expenses-a.csv` với `SAMPLE_A` hoặc `samples/expenses-b.csv` với `SAMPLE_B` ở màn hình nhập chi tiêu. Số tiền nhập là số nguyên đồng VND không kèm dấu phân nhóm. Khoảng ngày sao kê/thống kê tối đa 366 ngày theo UTC.

Khi chuyển tiền, cửa sổ xác nhận tạo một `requestKey`. Nếu hết thời gian chờ hoặc server lỗi, nút **Thử lại yêu cầu cũ** gửi lại chính khóa và nội dung đã xác nhận. Sau biên nhận thành công, desktop tải lại ví và lịch sử từ server. Cấp tiền ADMIN cũng tạo `requestKey` khi xác nhận và giữ nguyên mã ví, tiền, lý do khi kết quả chưa chắc chắn. Nút **Thử lại yêu cầu cấp tiền cũ** chỉ xuất hiện trong trường hợp đó; ứng dụng không tự gửi lại.

Ảnh demo được tạo với tài khoản và dữ liệu thử nghiệm trong [docs/demo](docs/demo): [đăng nhập](docs/demo/login.png), [dashboard](docs/demo/dashboard.png), [xác nhận/biên nhận](docs/demo/receipt.png), [lịch sử](docs/demo/history.png), [thống kê](docs/demo/statistics.png), [ADMIN](docs/demo/admin.png).

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

Build desktop độc lập:

```powershell
mvn -f desktop/pom.xml package
```

`DesktopApiIT` là smoke test tùy chọn, chạy với API thật trỏ vào **database thử nghiệm riêng**, biến môi trường `DEMO_ADMIN_EMAIL`/`DEMO_ADMIN_PASSWORD` cho tài khoản ADMIN thử nghiệm và `-Dit.api.url=...`. Test tạo tài khoản ngẫu nhiên, thực hiện cấp tiền/chuyển tiền/nhập CSV và chụp ảnh vào `desktop/target/demo/`; không chạy với database có dữ liệu thật. Hai test dùng HTTP server giả lập để kiểm tra retry chuyển tiền và cấp tiền cùng `requestKey`/payload sau lỗi 503. Không đặt mật khẩu hoặc token vào lệnh được chia sẻ, Git hay README.

Kết quả kiểm tra ngày 07/10/2026: backend unit 6/6; `WalletApiIT` 16/16 trên PostgreSQL cục bộ (gồm hai request cấp tiền cùng khóa chạy đồng thời); desktop `DesktopApiIT` 4/4 với API thật và HTTP giả lập, `WalletApiClientTest` 2/2. Build JAR backend và desktop thành công. Chưa chạy biến thể Testcontainers/Docker trong lần kiểm tra này.

## Kế hoạch mốc

1. Nền tảng: quy ước tiền VND nguyên đồng, quy tắc chuyển tiền, migration, API và pattern docs; `mvn test` xanh.
2. Spring Boot + PostgreSQL: xác thực, ví, chuyển tiền transaction, quản trị và kiểm thử tích hợp — đã triển khai.
3. Sao kê CSV/PDF, nhập CSV hai định dạng và thống kê theo danh mục/tháng — đã triển khai.
4. JavaFX FXML/CSS: đăng nhập, dashboard, chuyển tiền, lịch sử, sao kê, nhập CSV, thống kê và quản trị — đã triển khai trong `desktop/`.

Không commit `.env`, mật khẩu, token hoặc thông tin kết nối thật. Hai CSV trong `samples/` là dữ liệu chi tiêu giả lập và không sửa số dư ví.
