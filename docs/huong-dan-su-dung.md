# Hướng dẫn sử dụng Internal Wallet

Ứng dụng chạy trên trình duyệt tại [http://localhost:8080](http://localhost:8080) sau khi [khởi chạy bằng Docker Compose](../README.md#khởi-chạy-bằng-docker-compose). Nếu đã đổi `API_PORT` trong `.env`, dùng cổng tương ứng. Đây là ví nội bộ phục vụ học tập; các khoản chi nhập từ CSV là dữ liệu để thống kê, tách biệt với số dư ví và giao dịch chuyển tiền.

## Bắt đầu

1. Mở trang web, chọn **Tạo tài khoản** nếu chưa có tài khoản. Nhập họ tên, email và mật khẩu ít nhất 10 ký tự. Ví mới có số dư 0 VND.
2. Nếu đã có tài khoản, chọn **Đăng nhập** và nhập email, mật khẩu.
3. Ở **Tổng quan**, xem **Số dư khả dụng**, **Mã ví của bạn** và giao dịch gần đây. Có thể bấm **Sao chép mã ví** để gửi cho người sẽ chuyển tiền vào ví.

Tài khoản thường không tự cấp tiền cho ví. Chức năng **Cấp tiền vào ví** chỉ hiển thị với quản trị viên. Cách tạo tài khoản quản trị ban đầu nằm trong [README](../README.md#khởi-chạy-bằng-docker-compose).

## Chuyển tiền và xem lịch sử

1. Mở **Chuyển tiền**, nhập mã ví người nhận và số tiền nguyên VND, rồi bấm **Xác nhận chuyển tiền**.
2. Kiểm tra thông tin trong hộp xác nhận của trình duyệt trước khi đồng ý. Chuyển tiền thành công sẽ cập nhật số dư và lịch sử của các ví liên quan.
3. Mở **Lịch sử** để xem giao dịch chuyển đi hoặc nhận vào, ví đối ứng và số dư sau giao dịch. Dùng **Trước** / **Sau** để chuyển trang.

Nếu mạng gián đoạn và trang báo chưa rõ kết quả, dùng nút **Thử lại yêu cầu cũ** đang hiển thị. Đừng tạo một yêu cầu chuyển tiền mới khi chưa biết giao dịch cũ đã hoàn tất hay chưa.

## Chi tiêu: nhập CSV và xem thống kê

Mục **Chi tiêu** dùng để ghi các khoản chi từ một tệp CSV, chẳng hạn tiền ăn hoặc đi lại, rồi tổng hợp theo **Danh mục** hoặc **Tháng**. **Nhập chi tiêu không trừ số dư ví và không tạo giao dịch chuyển tiền.** Đây là dữ liệu riêng, không tự lấy từ mục Lịch sử hay Sao kê.

### Chuẩn bị tệp

Tải [tệp mẫu chi tiêu CSV](../samples/chi-tieu-mau.csv) ngay trên trang, thay các dòng ví dụ bằng khoản chi của mình và giữ nguyên dòng tiêu đề. Giao diện web dùng một định dạng: CSV UTF-8 có dấu nhận diện để Excel hiển thị tiếng Việt, phân cách bằng dấu phẩy; ngày theo `yyyy-MM-dd`, số tiền nguyên VND không có dấu phân nhóm.

Ví dụ:

```csv
Ngày,Nội dung,Danh mục,Số tiền
2026-10-05,Bữa trưa,Ăn uống,45000
2026-10-08,Xe buýt,Đi lại,12000
```

Tệp tối đa 1 MiB và 5.000 dòng dữ liệu. Cả bốn cột đều cần có giá trị hợp lệ; số tiền phải dương.

### Xem trước rồi xác nhận

1. Mở **Chi tiêu** → **Nhập khoản chi**, tải tệp mẫu nếu chưa có và chọn tệp chi tiêu CSV đã điền.
2. Bấm **Xem trước khoản chi**. Trang sẽ hiển thị số dòng, các dòng hợp lệ và lỗi theo dòng. Bước này **chưa lưu dữ liệu**.
3. Nếu có lỗi, sửa tệp và xem trước lại. Ứng dụng chỉ cho bấm **Xác nhận nhập dữ liệu** khi toàn bộ dòng hợp lệ.
4. Sau khi xác nhận và thấy thông báo đã nhập, chọn khoảng ngày ở **Thống kê chi tiêu**, chọn nhóm theo danh mục hoặc tháng, rồi bấm **Xem thống kê**.

Nếu kết quả nhập chưa rõ do mất kết nối, dùng **Thử lại yêu cầu cũ**. Ứng dụng giữ khóa yêu cầu để tránh nhập trùng khi thử lại cùng tệp.

### Vì sao tệp `statement-....csv` báo sai header?

Tệp `statement-....csv` tải từ mục **Sao kê** có sáu cột: thời gian, mã giao dịch, loại, ví đối ứng, số tiền và số dư sau giao dịch. Mục **Chi tiêu** chỉ nhận bốn cột của tệp mẫu chi tiêu. Nếu chọn nhầm tệp sao kê, trang sẽ báo rõ đây là CSV chuyển tiền và hướng dẫn tải tệp mẫu chi tiêu; **không có khoản chi nào được lưu**.

## Sao kê CSV/PDF

Mục **Sao kê** xuất các giao dịch **chuyển tiền** của ví trong khoảng ngày đã chọn, tính theo UTC. Khoản chi nhập từ CSV và khoản cấp tiền của quản trị viên không nằm trong sao kê này.

1. Mở **Sao kê**, chọn **Từ ngày**, **Đến ngày** và **Định dạng** CSV hoặc PDF.
2. Chọn **Toàn bộ** để tải tối đa 10.000 giao dịch trong một tệp. Với dữ liệu lớn, chọn **Từng phần** để tải 500 giao dịch mỗi tệp; nhập số phần bắt đầu từ 1.
3. Bấm **Tải sao kê**. Nếu tải từng phần, thông báo sẽ cho biết còn phần tiếp theo hay đã đến phần cuối.

CSV sao kê dùng để đọc hoặc lưu giao dịch bằng ứng dụng bảng tính. **Không đưa tệp này vào ô Nhập chi tiêu CSV** vì cấu trúc và mục đích của hai tệp khác nhau.

## Quản trị viên

Nếu tài khoản có quyền quản trị, tab **Quản trị** cho phép:

- **Cấp tiền vào ví:** nhập mã ví người nhận, số tiền và lý do; kiểm tra hộp xác nhận trước khi thực hiện. Khoản cấp được ghi vào nhật ký hệ thống.
- **Đối soát số dư:** bấm **Kiểm tra** để so sánh số dư từng ví với tổng bút toán. Báo cáo chỉ đọc dữ liệu; nếu có chênh lệch, cần kiểm tra nguyên nhân trước khi tiếp tục thao tác tài chính.

## Khi gặp lỗi thường gặp

- **Không đăng nhập được:** kiểm tra email, mật khẩu; nếu thử sai quá nhiều lần, đợi hết thời gian giới hạn rồi thử lại. Nếu phiên đăng nhập hết hạn, đăng nhập lại.
- **Header CSV không đúng định dạng:** tải tệp mẫu chi tiêu trên trang, giữ nguyên dòng tiêu đề và thay các dòng bên dưới. Không dùng CSV sao kê làm tệp chi tiêu.
- **Có dòng lỗi khi xem trước:** sửa các dòng được nêu rồi xem trước lại. Chưa có dòng nào được lưu khi tệp còn lỗi.
- **Không thấy khoản chi trong Lịch sử hoặc Sao kê:** đây là hành vi đúng. Khoản chi nhập CSV chỉ xuất hiện trong **Thống kê chi tiêu**.
- **Trang không mở được:** kiểm tra `docker compose ps`, cổng trong `.env` và hướng dẫn khởi chạy trong [README](../README.md).
