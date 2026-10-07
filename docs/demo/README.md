# Kiểm tra demo desktop trên Windows

Ảnh chụp từ JavaFX chạy với Spring Boot API và PostgreSQL thử nghiệm cục bộ ngày 07/10/2026. Tài khoản, mã ví và giao dịch đều là dữ liệu mẫu sinh trong `DesktopApiIT`; ảnh không chứa token hay mật khẩu.

| Luồng | Kết quả quan sát được | Mức xác nhận |
| --- | --- | --- |
| Sao kê CSV/PDF | Bấm `Tải CSV` và `Tải PDF` trên trang sao kê. Hai tệp được lưu, CSV mở đọc được bằng trình đọc văn bản và có mã giao dịch của ví đang đăng nhập. PDF bắt đầu bằng `%PDF-`, được render và xem trang đầu (`statement-preview.png`); giao diện báo đường dẫn lưu. | Đạt từ nút JavaFX qua API thật. Kiểm thử thay hộp thoại chọn nơi lưu bằng đường dẫn trong `desktop/target/demo/files`; thao tác tay trên hộp thoại Windows và mở bằng ứng dụng mặc định chưa xác nhận. |
| Nhập CSV và thống kê | Bấm `Chọn tệp CSV`, chọn mẫu B qua bộ chọn tệp của test, bấm `Nhập dữ liệu`. Giao diện báo nhập 2 dòng. Trang thống kê danh mục tăng từ 57.000 lên 100.000 VND; số dư ví vẫn 486.500 VND trước khi ADMIN cấp thêm. | Đạt từ nút JavaFX qua API thật. Kiểm thử thay hộp thoại mở tệp bằng `samples/expenses-b.csv`; thao tác tay trên hộp thoại Windows chưa xác nhận. |
| ADMIN cấp số dư | Đăng nhập ADMIN, mở trang cấp tiền, điền mã ví/số tiền 7.000 VND/lý do, bấm xác nhận và chấp nhận hộp thoại JavaFX. Giao diện báo mã thao tác; truy vấn ví người nhận từ server cho 493.500 VND. | Đạt qua giao diện JavaFX và API/PostgreSQL thật. |
| Giao dịch gần đây | Ví có một giao dịch hiển thị đúng một dòng; ví trống có danh sách 0 dòng, danh sách ẩn và thông báo trống riêng. | Đạt trong kiểm thử giao diện. |

Lệnh kiểm chứng: từ thư mục `desktop`, chạy `mvn -o -B -Dtest=DesktopApiIT -Dit.api.url=http://127.0.0.1:18080/api/v1 test` khi API và PostgreSQL thử nghiệm đang chạy. Test chủ động dùng dữ liệu mẫu và bộ chọn tệp thay thế để chạy lặp lại được; ứng dụng bình thường vẫn dùng `FileChooser` của JavaFX. Việc điều khiển hộp thoại native bằng `java.awt.Robot` đã thử nhưng bị timeout, nên chưa đánh dấu thao tác đó là đạt.

Ảnh: `login.png`, `dashboard.png`, `transfer.png`, `receipt.png`, `history.png`, `statements.png`, `statement-preview.png`, `import.png`, `statistics.png`, `statistics-imported.png`, `admin.png`.
