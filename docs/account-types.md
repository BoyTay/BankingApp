# Thiết kế mở rộng loại tài khoản

Tài liệu này là đầu ra chặng 1 trước khi đổi schema và API. Ứng dụng hiện có đúng một ví cho mỗi người dùng: `wallets.owner_id` là duy nhất, số dư không âm, và các luồng chuyển tiền, sao kê, đối soát đều dựa trên giả định đó.

## Quy tắc chung đã xác định

- Ba loại sản phẩm là **Thanh toán**, **Tiết kiệm** và **Tín dụng**. Loại sản phẩm độc lập với quyền đăng nhập `USER`/`ADMIN`.
- Mọi tài khoản có ID riêng, chủ sở hữu, mã công khai, loại, trạng thái và đơn vị tiền VND. API phải kiểm tra chủ sở hữu của **tài khoản nguồn** ở mỗi thao tác; biết mã tài khoản của người khác không cấp quyền xem lịch sử hoặc sao kê của họ.
- Mọi thay đổi số dư phải ghi bút toán cùng một transaction. Đối soát so sánh từng tài khoản với tổng bút toán của chính tài khoản đó.
- Mỗi loại tài khoản có quy tắc phí riêng. Phí phải được hiển thị trước khi xác nhận, lưu thành bút toán có nguồn rõ ràng và có khóa chống thu trùng. Chuyển tiền vẫn ghi đúng số tiền gửi/nhận; phí được ghi riêng để sao kê và đối soát giải thích được.
- `requestKey` chống ghi trùng theo tài khoản nguồn và nội dung yêu cầu. Khi thử lại cùng khóa, API trả lại kết quả cũ; cùng khóa nhưng nội dung khác trả xung đột.
- Mỗi người dùng mới có một tài khoản Thanh toán mặc định với số dư 0. Khi mở tài khoản khác, Factory Method tạo cấu hình ban đầu; Strategy/Policy riêng quyết định tài khoản được ghi nợ, ghi có hoặc tính lãi như thế nào.
- Tài khoản có lịch sử giao dịch không bị xóa vật lý qua API. Về sau sẽ dùng trạng thái đóng tài khoản và chỉ cho đóng khi nghĩa vụ, số dư còn lại đã xử lý. Việc reset dữ liệu phát triển là thao tác vận hành riêng.

## Quy tắc đề xuất cho phiên bản đầu

- **Thanh toán:** giữ quy tắc hiện tại: không âm, chuyển đi khi đủ số dư, nhận tiền tự do. Phí duy trì minh họa 5.000 VND mỗi tháng cho mỗi tài khoản.
- **Tiết kiệm:** người dùng nạp từ tài khoản Thanh toán của chính mình khi mở khoản tiết kiệm. Không nạp thêm; được rút trước ngày đáo hạn với phí 0,5% trên số tiền rút, hoặc rút đúng hạn không mất phí. Sau đáo hạn được chuyển gốc và lãi về Thanh toán của chính chủ. Lãi suất cố định tại thời điểm mở, tính theo số ngày thực tế và ghi thành một bút toán duy nhất khi đáo hạn. Kỳ hạn và lãi suất là cấu hình của sản phẩm, không được ngầm lấy một mức lãi thật của ngân hàng.
- **Tín dụng:** bản đầu mô phỏng dư nợ và hạn mức do ADMIN cấp, chưa dùng hạn mức làm nguồn chuyển tiền. Dư nợ không vượt hạn mức; hoàn trả từ tài khoản Thanh toán của chính chủ và không cho trả quá dư nợ. Phí thường niên minh họa 20.000 VND mỗi năm; chưa tính lãi tín dụng. Nếu lưu dư nợ bằng số dư âm, chỉ tài khoản Tín dụng được âm; Thanh toán và Tiết kiệm vẫn không âm. Chỉ bổ sung chuyển tiền từ hạn mức sau khi mô hình dư nợ, bút toán và đối soát đã ổn định.
- Phí định kỳ không được đẩy tài khoản Thanh toán xuống âm. Khi thiếu tiền, lưu khoản phí đến hạn và thử thu lại theo lịch; không từ chối đăng nhập hay khóa tài khoản. Mức phí, ngày hiệu lực và cách làm tròn phải được cấu hình và lưu cùng giao dịch để thay đổi cấu hình không sửa lịch sử.

## Việc cần xác nhận trước thao tác dữ liệu

- **Reset dữ liệu hiện có:** phải xác định đúng tài khoản hoặc phạm vi dữ liệu trước khi xóa. Database đang có người dùng, ví, giao dịch và khoản chi; chưa thực hiện thao tác xóa. Migration chặng 2 sẽ bảo toàn dữ liệu cho đến khi phạm vi reset được xác nhận.
- Các mức kỳ hạn, lãi suất và hạn mức mặc định sẽ được chốt trước chặng triển khai sản phẩm tương ứng. Không dùng các giá trị này để thay đổi số dư tài khoản hiện có.

## Các tình huống kiểm thử bắt buộc

1. Người dùng mới có đúng một tài khoản Thanh toán mặc định, số dư 0; tài khoản cũ vẫn đọc được sau migration nếu không reset dữ liệu.
2. Một người có nhiều tài khoản nhưng chỉ xem, chuyển tiền, tải sao kê của tài khoản mình sở hữu. Mã tài khoản đích chỉ dùng để nhận tiền.
3. Chuyển tiền đồng thời không tạo số dư sai, không vượt quy tắc của loại tài khoản và không ghi thiếu bút toán.
4. Thử lại cùng `requestKey` không chuyển hoặc nhập tiền lần thứ hai; khóa trùng với nội dung khác bị từ chối.
5. Ghi lãi Tiết kiệm đúng kỳ và chỉ một lần; việc rút tiền tuân theo quy tắc đã chốt.
6. Tín dụng không vượt hạn mức; hoàn trả làm giảm dư nợ đúng số tiền; lỗi giữa transaction không để lại bút toán hoặc số dư dở dang.
7. Sao kê và đối soát chính xác cho từng tài khoản, kể cả sau khi tài khoản đóng.
8. Phí mỗi loại được tính và thu đúng một lần, có bút toán riêng; chuyển 50.000 VND thì nguồn giảm và đích tăng đúng 50.000 VND trước khoản phí độc lập. Thiếu tiền để thu phí định kỳ không làm âm Thanh toán hoặc chặn đăng nhập.

## Thứ tự triển khai

1. Chặng 2: đổi schema sang nhiều tài khoản trên một người dùng, vẫn chỉ mở Thanh toán; cập nhật API theo account ID và giữ tài khoản mặc định để giao diện hiện tại hoạt động.
2. Chặng 3: Factory Method tạo tài khoản theo loại, Policy cho quy tắc giao dịch và hạ tầng bút toán phí. Phí Thanh toán bắt đầu từ ngày đầu tháng sau khi áp dụng biểu phí; việc tự động thu chỉ bật sau khi giao diện hiển thị rõ phí.
3. Chặng 4: triển khai Tiết kiệm với bút toán lãi, phí rút trước hạn và kiểm thử kỳ hạn.
4. Chặng 5: triển khai Tín dụng và phí thường niên, sau đó hoàn thiện giao diện, sao kê, đối soát và hướng dẫn.
