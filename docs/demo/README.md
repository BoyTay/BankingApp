# Ảnh demo desktop mốc 4

Ảnh được chụp từ JavaFX chạy trên Windows với Spring Boot API và PostgreSQL thử nghiệm cục bộ. Tài khoản, mã ví và giao dịch trong ảnh đều là dữ liệu mẫu sinh cho smoke test; không có token hoặc mật khẩu trong ảnh.

- `login.png`: cửa sổ đăng nhập.
- `dashboard.png`: số dư và giao dịch gần đây sau cấp tiền và chuyển tiền mẫu.
- `transfer.png`: biểu mẫu tra cứu/chuyển tiền.
- `receipt.png`: biên nhận sau khi xác nhận chuyển tiền qua giao diện.
- `history.png`: lịch sử phân trang.
- `statistics.png`: thống kê danh mục từ CSV mẫu.
- `admin.png`: màn hình cấp số dư chỉ xuất hiện với ADMIN.

Smoke test `DesktopApiIT` đã nạp tất cả trang với API thật, xác nhận đăng ký/đăng nhập/đăng xuất qua biểu mẫu, xác nhận chuyển tiền qua giao diện và đối chiếu số dư từ server, mở chi tiết lịch sử, đổi thống kê danh mục/tháng. Đã gọi API tải CSV/PDF, nhập CSV và cấp tiền ADMIN; hộp chọn tệp của các trang sao kê/nhập CSV và nút cấp tiền trên giao diện chưa được tự động bấm trong smoke test.
