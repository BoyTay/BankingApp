# Vận hành Docker Compose

## Trạng thái dịch vụ

`docker compose ps` cho biết trạng thái của `db` và `api`. API có health check tại `http://localhost:8080/actuator/health` (thay cổng nếu đã đặt `API_PORT`). Endpoint trả `UP` khi ứng dụng và kết nối PostgreSQL hoạt động; chi tiết thành phần không được công khai. Container `api` được đánh dấu `healthy` sau khi health check thành công.

Giới hạn thử đăng nhập là 10 lần mỗi 60 giây theo cặp địa chỉ kết nối và email đã chuẩn hóa; đăng ký là 20 lần mỗi 60 giây theo địa chỉ kết nối. Yêu cầu vượt giới hạn trả HTTP `429 RATE_LIMITED` và header `Retry-After`. Đăng nhập thành công xóa bộ đếm của cặp đó. Giới hạn tự hết hạn sau tối đa 60 giây, không khóa tài khoản vĩnh viễn. Ứng dụng dùng địa chỉ kết nối trực tiếp, không tin header `X-Forwarded-For`; khi đặt sau reverse proxy, cần thiết kế lại cách nhận IP từ proxy tin cậy trước khi mở API cho nhiều người dùng.

Mỗi giờ ứng dụng xóa phiên hết hạn hoặc đã đăng xuất và các bộ đếm giới hạn đã hết hạn. Việc xóa không ảnh hưởng đến phiên còn hiệu lực.

## Sao lưu PostgreSQL

Chạy các lệnh PowerShell sau tại thư mục gốc dự án. Tệp `.dump` nằm trên máy của bạn và đã được Git bỏ qua:

```powershell
docker compose exec -T db pg_dump -U wallet_app -d internal_wallet -Fc -f /tmp/internal-wallet.dump
docker compose exec -T db pg_restore --list /tmp/internal-wallet.dump
docker compose cp db:/tmp/internal-wallet.dump .\internal-wallet.dump
docker compose exec -T db rm -f /tmp/internal-wallet.dump
```

`pg_dump -Fc` tạo bản sao nhất quán khi database đang chạy. Giữ tệp sao lưu ở nơi an toàn vì nó chứa dữ liệu tài khoản và giao dịch. Không dùng dấu `>` để chuyển dữ liệu nhị phân của `pg_dump` qua PowerShell 5.1.

## Khôi phục

Thử khôi phục trên môi trường riêng trước. Khi cần thay dữ liệu của database Compose hiện tại, giữ lại một bản sao lưu mới rồi dừng API để không có ghi mới trong lúc khôi phục:

```powershell
docker compose stop api
docker compose cp .\internal-wallet.dump db:/tmp/internal-wallet.dump
docker compose exec -T db pg_restore -U wallet_app -d internal_wallet --clean --if-exists --no-owner --no-privileges --single-transaction /tmp/internal-wallet.dump
docker compose exec -T db rm -f /tmp/internal-wallet.dump
docker compose start api
docker compose ps
```

`--single-transaction` giúp toàn bộ thao tác khôi phục thành công hoặc rollback khi có lỗi. Nếu `pg_restore` báo lỗi, kiểm tra nguyên nhân và giữ API dừng; chỉ khởi động lại sau khi đã xử lý xong. Bản sao lưu nên cùng phiên bản schema ứng dụng (Flyway) với image API sẽ khởi động.

Tham khảo: [PostgreSQL pg_dump](https://www.postgresql.org/docs/17/app-pgdump.html), [PostgreSQL pg_restore](https://www.postgresql.org/docs/17/app-pgrestore.html), [Docker Compose cp](https://docs.docker.com/reference/cli/docker/compose/cp/).

## Email thông báo (Mailpit)

Compose chạy thêm `mailpit` làm máy chủ SMTP thử nghiệm: mọi email của ứng dụng được giữ lại ở `http://localhost:8025` (chỉ mở trên `127.0.0.1`, không có xác thực) và không ra Internet. Biến môi trường: `NOTIFY_EMAIL_ENABLED` (mặc định `true` trong Compose, `false` khi chạy ngoài Compose), `NOTIFY_REMINDERS_ENABLED` (tác vụ nhắc nhở hằng ngày 00:15 UTC), `MAILPIT_PORT`. Mailpit không lưu dữ liệu qua lần khởi động lại và email gửi lỗi chỉ được ghi log; thông báo trong ứng dụng vẫn được lưu trong PostgreSQL. Để gửi email thật, đổi `MAIL_HOST`/`MAIL_PORT` sang máy chủ SMTP khác và bổ sung thông tin đăng nhập.
