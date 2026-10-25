# Kịch bản kiểm thử thủ công — Internal Wallet

Tài liệu này dùng để tự kiểm tra từng chức năng trên giao diện web. Mỗi mục có **bước thực hiện** và **kết quả mong đợi**. Đánh dấu `[x]` khi đạt; nếu sai, ghi lại bước, kết quả thực tế và `traceId` (nếu giao diện hiển thị).

## 0. Chuẩn bị môi trường

**Lưu ý cổng 8080:** nếu cổng 8080 đang bị container khác chiếm (ví dụ `calcom-api`), chạy BankingApp ở cổng khác.

```powershell
# Trong thư mục dự án; .env cần có DB_PASSWORD và (tùy chọn) tài khoản ADMIN ban đầu
#   DB_PASSWORD=...
#   APP_BOOTSTRAP_ADMIN_EMAIL=admin@example.test
#   APP_BOOTSTRAP_ADMIN_PASSWORD=AdminPass12345!
#   API_PORT=8081
docker compose up -d --build
docker compose ps          # db: healthy, api và mailpit: running
```

- Ứng dụng: `http://localhost:8081` (đổi theo `API_PORT`). Hộp thư thử nghiệm Mailpit: `http://localhost:8025`.
- Kiểm tra sức khỏe: mở `http://localhost:8081/actuator/health` → trạng thái `UP`.
- Lệnh SQL tiện dụng (dùng ở các mục cần chỉnh thời gian):

```powershell
docker compose exec db psql -U wallet_app -d internal_wallet -c "SELECT ..."
```

**Dữ liệu dùng chung (tạo ở mục 1, 2):**

| Tên | Vai trò | Ghi chú |
|---|---|---|
| ADMIN | Quản trị | Tài khoản bootstrap ở `.env` |
| An | USER | `an@example.test` |
| Bình | USER | `binh@example.test` |
| Chi | USER | `chi@example.test`, dùng cho kiểm tra phân quyền |

Mật khẩu mẫu: `StrongPass123!`. Mở **hai trình duyệt/cửa sổ ẩn danh** để đăng nhập hai người cùng lúc.

Đồng bộ theo giờ: các tác vụ tự động chạy theo UTC; giờ Việt Nam = UTC+7.

---

## 1. Đăng ký

| # | Bước | Kết quả mong đợi | Đạt |
|---|---|---|---|
| 1.1 | Mở trang, chọn **Tạo tài khoản**, nhập `An`, `an@example.test`, `StrongPass123!` | Tạo thành công, vào được ứng dụng (hoặc chuyển sang đăng nhập); ví mới có **0 VND** và có **Mã ví** dạng `WLT…` (23 ký tự) | [ ] |
| 1.2 | Đăng ký lại cùng email, viết hoa/thừa khoảng trắng (`  AN@example.test `) | Báo email đã được sử dụng, không tạo tài khoản mới | [ ] |
| 1.3 | Mật khẩu 9 ký tự (`abcdefghi`) | Báo mật khẩu yếu (cần 10–128 ký tự) | [ ] |
| 1.4 | Mật khẩu đúng 10 ký tự (`abcdefghij`) với email mới | Thành công | [ ] |
| 1.5 | Email sai dạng: `an`, `an@`, `an@example`, `a b@example.test` | Báo email không hợp lệ cho từng trường hợp | [ ] |
| 1.6 | Tên hiển thị để trống; tên 121 ký tự | Báo lỗi tên hiển thị | [ ] |
| 1.7 | Đăng ký Bình (`binh@example.test`) và Chi (`chi@example.test`) | Thành công; mỗi người có mã ví khác nhau | [ ] |
| 1.8 | **Lỗi đã biết:** mật khẩu 100 ký tự (`a` × 100) | **Kỳ vọng theo tài liệu:** thành công. **Thực tế dự kiến:** lỗi máy chủ (BCrypt giới hạn 72 byte). Ghi lại kết quả | [ ] |

## 2. Đăng nhập, đăng xuất, phiên

| # | Bước | Kết quả mong đợi | Đạt |
|---|---|---|---|
| 2.1 | Đăng nhập An với mật khẩu đúng | Vào **Tổng quan**, thấy số dư, mã ví | [ ] |
| 2.2 | Đăng nhập sai mật khẩu; email không tồn tại | Cùng một thông báo "Email hoặc mật khẩu không đúng" (không lộ email có tồn tại hay không) | [ ] |
| 2.3 | Đăng nhập sai liên tiếp 11 lần trong 60 giây | Từ lần thứ 11 bị chặn (giới hạn tần suất), thông báo chờ; sau ~60 giây đăng nhập đúng lại được | [ ] |
| 2.4 | Đăng nhập đúng, bấm **Đăng xuất** | Về màn hình đăng nhập; nút Back của trình duyệt không cho xem dữ liệu | [ ] |
| 2.5 | Đăng nhập, tải lại trang (F5) | Bị đăng xuất (token chỉ giữ trong bộ nhớ tab) — đúng thiết kế | [ ] |
| 2.6 | Mở trang ở hai tab, đăng xuất ở tab 1, thao tác ở tab 2 | Tab 2 báo phiên hết hạn / yêu cầu đăng nhập lại (không hiện lỗi kỹ thuật) | [ ] |

## 3. Tổng quan

| # | Bước | Kết quả mong đợi | Đạt |
|---|---|---|---|
| 3.1 | Ví mới, mở **Tổng quan** | Số dư 0; tiền vào/ra tháng này 0; biểu đồ trống hoặc có thông báo "chưa có dữ liệu" | [ ] |
| 3.2 | Bấm **Sao chép mã ví** | Mã ví vào clipboard (dán ra ô khác để kiểm tra) | [ ] |
| 3.3 | Sau khi có giao dịch (mục 5, 6) | Số dư, tiền vào/ra, giao dịch gần đây và biểu đồ biến động khớp với **Lịch sử** | [ ] |

## 4. Quản trị — cấp tiền (cần ADMIN)

| # | Bước | Kết quả mong đợi | Đạt |
|---|---|---|---|
| 4.1 | Đăng nhập An (USER) | **Không** thấy tab **Quản trị** | [ ] |
| 4.2 | Đăng nhập ADMIN, mở **Quản trị → Cấp tiền vào ví**: mã ví An, số tiền `1000000`, lý do `So du demo` → xác nhận | Hộp xác nhận hiện đúng thông tin; thành công; số dư An thành **1.000.000** | [ ] |
| 4.3 | Với An: mở **Thông báo** | Có thông báo nhận tiền cấp; Mailpit có email tương ứng | [ ] |
| 4.4 | Cấp tiền cho Bình `500000` | Thành công | [ ] |
| 4.5 | Số tiền `0`, `-5`, `1.5`, `1000000000001`, để trống | Mỗi trường hợp báo lỗi số tiền, không cấp | [ ] |
| 4.6 | Lý do để trống/chỉ khoảng trắng; lý do 501 ký tự | Báo lỗi lý do, không cấp | [ ] |
| 4.7 | Mã ví không tồn tại (`WLT00000000000000000000`) | Báo không tìm thấy ví | [ ] |
| 4.8 | Cấp tiền với số tiền lớn nhất `1000000000000` cho Chi | Thành công; số dư Chi = 1.000.000.000.000 (kiểm tra hiển thị không bị cắt/vỡ bố cục) | [ ] |

## 5. Chuyển tiền

Cần: An có 1.000.000, Bình có 500.000.

| # | Bước | Kết quả mong đợi | Đạt |
|---|---|---|---|
| 5.1 | An → **Chuyển tiền**: nhập mã ví Bình, `275000`, **Xác nhận chuyển tiền** | Hộp xác nhận hiện **tên người nhận (Bình)**; đồng ý → thành công; An còn **725.000**, Bình **775.000** | [ ] |
| 5.2 | Nhập mã ví sai (`WLTxxxx`) | Báo lỗi trước khi chuyển; số dư không đổi | [ ] |
| 5.3 | Chuyển cho chính mã ví của An | Báo không thể tự chuyển | [ ] |
| 5.4 | Số tiền `0`, `-1`, `1.5`, `abc`, để trống | Báo lỗi số tiền | [ ] |
| 5.5 | Chuyển `5000000` (lớn hơn số dư) | Báo **Số dư không đủ**; số dư không đổi | [ ] |
| 5.6 | Chuyển đúng toàn bộ số dư còn lại (725.000) | Thành công; An còn **0** | [ ] |
| 5.7 | Nhấn **Esc** hoặc **Hủy** ở hộp xác nhận | Không có giao dịch nào được tạo | [ ] |
| 5.8 | Nhấp đúp nhanh nút xác nhận | Chỉ **một** giao dịch được tạo (kiểm tra ở Lịch sử) | [ ] |
| 5.9 | Mô phỏng mất mạng: DevTools → Network → Offline, xác nhận chuyển, rồi bật lại mạng, bấm **Thử lại yêu cầu cũ** | Kết quả cuối chỉ có **một** giao dịch; không bị trừ tiền hai lần | [ ] |
| 5.10 | Bình kiểm tra Thông báo/Lịch sử | Thấy khoản nhận, hướng **INCOMING/Nhận vào**, số dư sau là số dư **của Bình** | [ ] |
| 5.11 | Kiểm tra tổng | Tổng số dư tất cả ví (trừ khoản cấp) không đổi sau mọi lần chuyển | [ ] |

## 6. Lịch sử chuyển tiền

| # | Bước | Kết quả mong đợi | Đạt |
|---|---|---|---|
| 6.1 | Tạo ≥ 25 giao dịch nhỏ (có thể chuyển 1.000 VND nhiều lần) rồi mở **Lịch sử** | Có phân trang; **Trước/Sau** hoạt động; trang đầu là giao dịch **mới nhất** | [ ] |
| 6.2 | Kiểm tra từng dòng | Có hướng chuyển/nhận, ví đối ứng, số tiền, số dư sau; **không** hiện số dư của ví đối ứng | [ ] |
| 6.3 | Chi (người không liên quan) mở Lịch sử | Không thấy giao dịch của An/Bình | [ ] |
| 6.4 | Ở trang cuối bấm **Sau** | Nút bị vô hiệu hoặc không có dữ liệu thừa | [ ] |

## 7. Tài khoản: Thanh toán, Tiết kiệm, Tín dụng

### 7a. Thanh toán phụ

| # | Bước | Kết quả mong đợi | Đạt |
|---|---|---|---|
| 7a.1 | **Tài khoản → Mở tài khoản**, loại **Thanh toán** | Hiển thị phí trước khi xác nhận (5.000 VND/tháng); tạo xong có **số dư 0**, không thay tài khoản mặc định | [ ] |
| 7a.2 | ADMIN cấp 100.000 cho mã tài khoản phụ | Cấp thành công | [ ] |
| 7a.3 | **Chuyển tiền** chọn nguồn là tài khoản phụ, chuyển 40.000 cho Bình | Tài khoản phụ còn 60.000; tài khoản mặc định không đổi | [ ] |
| 7a.4 | **Lịch sử** chọn tài khoản phụ | Chỉ thấy giao dịch của tài khoản phụ | [ ] |
| 7a.5 | Đóng tài khoản phụ khi còn 60.000 | Báo không thể đóng (còn số dư) | [ ] |
| 7a.6 | Chuyển hết 60.000 đi rồi đóng | Đóng thành công, trạng thái **CLOSED**; lịch sử vẫn xem được | [ ] |
| 7a.7 | Thử đóng tài khoản **mặc định** | Không có nút đóng hoặc báo không được phép | [ ] |
| 7a.8 | Người dùng khác (Chi) truy cập tài khoản của An (dán `/api/v1/me/accounts/<id>` bằng token của Chi) | Báo không tìm thấy tài khoản | [ ] |

### 7b. Tiết kiệm (cần An có tối thiểu 300.000 trong Thanh toán)

| # | Bước | Kết quả mong đợi | Đạt |
|---|---|---|---|
| 7b.1 | Mở **Tiết kiệm**, nguồn = Thanh toán, số tiền `99999` | Báo tiền gửi tối thiểu 100.000; không tạo | [ ] |
| 7b.2 | Mở **Tiết kiệm** `200000` | Thành công; Thanh toán giảm 200.000; thẻ Tiết kiệm hiện ngày đáo hạn (+90 ngày) và lãi 4%/năm | [ ] |
| 7b.3 | Từ Tiết kiệm thử chuyển tiền/nhận cấp tiền | Không cho phép (loại tài khoản không hỗ trợ) | [ ] |
| 7b.4 | **Tất toán sớm** (chưa đến hạn) | Phí **1.000** (0,5% × 200.000), lãi 0, nhận **199.000** về Thanh toán; trạng thái CLOSED, số dư 0 | [ ] |
| 7b.5 | Bấm tất toán lần nữa | Không trừ/cộng thêm; báo đã tất toán | [ ] |
| 7b.6 | Kiểm tra làm tròn: mở 100.100 rồi tất toán sớm | Phí **501** (500,5 làm tròn lên); mở 100.001 → phí **500** | [ ] |
| 7b.7 | **Đáo hạn:** mở Tiết kiệm 200.000, rồi chỉnh ngày đáo hạn về hôm qua (SQL bên dưới), tất toán | Không mất phí; lãi **1.973**; nhận **201.973** | [ ] |
| 7b.8 | Chỉnh đáo hạn về 60 ngày trước rồi tất toán | Lãi vẫn **1.973** (rút muộn không thêm lãi) | [ ] |

```sql
-- 7b.7/7b.8: đổi ngày đáo hạn của tiết kiệm mới nhất của An
UPDATE savings_accounts SET matures_on = CURRENT_DATE - 1
WHERE wallet_id = (SELECT id FROM wallets WHERE account_type='SAVINGS' ORDER BY created_at DESC LIMIT 1);
```

### 7c. Tín dụng mô phỏng

| # | Bước | Kết quả mong đợi | Đạt |
|---|---|---|---|
| 7c.1 | An mở tài khoản **Tín dụng** | Hạn mức **0**, dư nợ 0; thanh hạn mức hiển thị | [ ] |
| 7c.2 | An ghi khoản sử dụng 1 VND khi hạn mức 0 | Báo vượt hạn mức | [ ] |
| 7c.3 | An (USER) gọi cấp hạn mức | Không có chức năng / bị từ chối (chỉ ADMIN) | [ ] |
| 7c.4 | ADMIN: **Quản trị → Hạn mức tín dụng**, nhập UUID tài khoản Tín dụng của An, hạn mức `100000` | Thành công; thẻ Tín dụng của An hiện hạn mức 100.000 | [ ] |
| 7c.5 | An ghi sử dụng `60000`, mô tả `Minh hoa` | Dư nợ **60.000**; thanh hạn mức ~60% | [ ] |
| 7c.6 | An ghi tiếp `40000` (vừa đủ hạn mức), rồi `1` | 40.000 thành công (dư nợ 100.000; thanh chuyển **đỏ** ≥ 90%); `1` bị báo vượt hạn mức | [ ] |
| 7c.7 | ADMIN hạ hạn mức xuống 50.000 khi dư nợ 100.000 | Báo hạn mức thấp hơn dư nợ | [ ] |
| 7c.8 | An hoàn trả 100.001 | Báo vượt dư nợ; số dư Thanh toán không đổi | [ ] |
| 7c.9 | An hoàn trả 100.000 từ Thanh toán (cần đủ tiền) | Dư nợ 0; Thanh toán giảm 100.000 | [ ] |
| 7c.10 | Chuyển tiền trực tiếp từ/đến Tín dụng | Không cho phép | [ ] |
| 7c.11 | Đóng Tín dụng khi dư nợ 0 và không còn phí đến hạn | Đóng thành công | [ ] |
| 7c.12 | **Quản trị → Đối soát → Kiểm tra** sau tất cả thao tác trên | **0 ví sai lệch** | [ ] |

### 7d. Phí tự động

Phí tạo/thu bởi tác vụ chạy lúc **00:05 UTC (07:05 giờ Việt Nam)** hằng ngày; không có nút kích hoạt thủ công.

| # | Bước | Kết quả mong đợi | Đạt |
|---|---|---|---|
| 7d.1 | Mở Thanh toán phụ mới | Phí bắt đầu từ **ngày đầu tháng sau**; ngay lúc mở chưa có phí nào | [ ] |
| 7d.2 | Để kiểm tra nhanh: chỉnh `fee_starts_on` về hôm qua (SQL), chờ qua 07:05 sáng hôm sau | Xuất hiện phí 5.000 trạng thái **DUE** (nếu thiếu tiền) hoặc **PAID** (đủ tiền) trong chi tiết tài khoản | [ ] |
| 7d.3 | Thiếu tiền để thu phí | Phí giữ **DUE**, số dư **không âm**, vẫn đăng nhập bình thường | [ ] |
| 7d.4 | Nạp đủ tiền, chờ lần chạy kế tiếp | Phí chuyển sang **PAID**, chỉ thu **một lần** | [ ] |

```sql
UPDATE wallets SET fee_starts_on = CURRENT_DATE - 1 WHERE id = '<uuid-tài-khoản>';
```

## 8. Thông báo và email

| # | Bước | Kết quả mong đợi | Đạt |
|---|---|---|---|
| 8.1 | Sau mục 4–5, mở **Thông báo** | Có thông báo cấp tiền, chuyển đi, nhận về; badge chưa đọc đúng số | [ ] |
| 8.2 | Mở Mailpit `http://localhost:8025` | Mỗi thông báo có một email tương ứng | [ ] |
| 8.3 | Đánh dấu một thông báo đã đọc | Số chưa đọc giảm 1 | [ ] |
| 8.4 | **Đọc tất cả** hai lần liên tiếp | Số chưa đọc về 0, lần hai không lỗi | [ ] |
| 8.5 | Chi chỉ thấy thông báo của chính mình | Không thấy của An/Bình | [ ] |
| 8.6 | Chờ ≤ 30 giây khi người khác chuyển tiền cho bạn | Badge tự cập nhật không cần tải lại | [ ] |
| 8.7 | Cảnh báo số dư thấp: An còn 100.000, đặt ngưỡng `50000` → **Lưu ngưỡng** | Lưu thành công, chưa có cảnh báo | [ ] |
| 8.8 | An chuyển đi 60.000 (còn 40.000 < 50.000) | Có **một** cảnh báo số dư thấp | [ ] |
| 8.9 | An chuyển tiếp 10.000 (còn 30.000) | **Không** thêm cảnh báo (đã báo rồi) | [ ] |
| 8.10 | Nạp lại cho An ≥ 50.000 rồi chuyển xuống dưới ngưỡng lần nữa | Cảnh báo **được gửi lại** (cảnh báo thứ 2) | [ ] |
| 8.11 | Đặt ngưỡng `0` | Tắt cảnh báo; không có cảnh báo mới | [ ] |
| 8.12 | Đặt ngưỡng `-1` hoặc `abc` | Báo lỗi số tiền | [ ] |
| 8.13 | Nhắc nhở (tác vụ 00:15 UTC = 07:15 VN): có phí DUE, có dư nợ tín dụng, có Tiết kiệm sắp đáo hạn ≤ 3 ngày | Nhận thông báo nhắc tương ứng; **không lặp** trong cùng kỳ (phí: 1 lần/tuần, nợ: 1 lần/tháng, tiết kiệm: 1 lần/khoản) | [ ] |

## 9. Chi tiêu: nhập CSV và thống kê

Tệp mẫu: `samples/chi-tieu-mau.csv`.

| # | Bước | Kết quả mong đợi | Đạt |
|---|---|---|---|
| 9.1 | **Chi tiêu**: tải tệp mẫu, chọn tệp, **Xem trước khoản chi** | Hiện số dòng, dòng hợp lệ, **chưa lưu**; nút **Xác nhận nhập dữ liệu** bật khi không có lỗi | [ ] |
| 9.2 | **Xác nhận nhập dữ liệu** | Thông báo đã nhập; **số dư ví không đổi**, không có giao dịch mới ở Lịch sử | [ ] |
| 9.3 | **Thống kê** khoảng ngày chứa dữ liệu, nhóm **Danh mục** → **Xem thống kê** | Biểu đồ tròn + danh sách có cùng số liệu; tổng đúng | [ ] |
| 9.4 | Đổi sang nhóm **Tháng** | Biểu đồ cột; **tổng bằng** tổng theo danh mục | [ ] |
| 9.5 | Nhập lại **đúng tệp đó** | Không nhập trùng (dữ liệu/thống kê không tăng gấp đôi) | [ ] |
| 9.6 | Tạo tệp có dòng lỗi: ngày `2026-13-01`, số tiền `1.5`, số tiền `0`, thiếu cột, nội dung trống | Xem trước liệt kê **lỗi theo từng dòng**; **không** cho xác nhận; không lưu dòng nào | [ ] |
| 9.7 | Chọn nhầm tệp sao kê (`statement-….csv`) | Báo rõ đây là CSV chuyển tiền, hướng dẫn tải tệp mẫu; không lưu | [ ] |
| 9.8 | Tệp sai header; tệp rỗng; chỉ có header | Báo lỗi cấp tệp tương ứng | [ ] |
| 9.9 | Tệp không phải UTF-8 (lưu bằng ANSI) | Báo tệp không phải UTF-8 hợp lệ | [ ] |
| 9.10 | Tệp > 1 MiB | Báo tệp quá lớn (413) | [ ] |
| 9.11 | Tệp 5.000 dòng hợp lệ; tệp 5.001 dòng | 5.000: xem trước được; 5.001: báo vượt 5.000 dòng | [ ] |
| 9.12 | Dòng nội dung bắt đầu bằng `=1+1` | Hiển thị là chữ thường, không bị tính như công thức | [ ] |
| 9.13 | Bình xem thống kê | Không thấy khoản chi của An | [ ] |
| 9.14 | Ngày ngoài 2000–2100 trong tệp | Báo lỗi dòng tương ứng | [ ] |

## 10. Sao kê CSV/PDF

| # | Bước | Kết quả mong đợi | Đạt |
|---|---|---|---|
| 10.1 | **Sao kê**: từ ngày đầu năm đến cuối năm, **CSV**, **Toàn bộ** → **Tải sao kê** | Tải tệp `statement-YYYYMMDD-YYYYMMDD.csv` | [ ] |
| 10.2 | Mở CSV bằng Excel | Tiếng Việt hiển thị đúng; tiêu đề `Thời gian UTC, Mã giao dịch, Loại, Ví đối ứng, Số tiền (VND), Số dư sau (VND)` | [ ] |
| 10.3 | Đối chiếu từng dòng với **Lịch sử** | Khớp số tiền, loại, số dư sau (số dư **của bạn**); không có số dư ví đối ứng | [ ] |
| 10.4 | Tải **PDF** | Mở được; font tiếng Việt đúng; có tiêu đề, mã ví, khoảng ngày, các dòng giao dịch, tổng vào/ra | [ ] |
| 10.5 | Khoảng ngày 366 ngày trở lên (vd 01/01/2026–02/01/2027) | Báo khoảng ngày không hợp lệ | [ ] |
| 10.6 | `Từ ngày` > `Đến ngày`; để trống một ô | Báo lỗi khoảng ngày | [ ] |
| 10.7 | Khoảng 1 ngày (từ = đến) | Tải được | [ ] |
| 10.8 | **Từng phần**, phần 1 (500 giao dịch/phần) | Tên tệp có `-page-…`; thông báo cho biết còn phần tiếp theo hay đã hết | [ ] |
| 10.9 | Phần quá lớn/không hợp lệ (phần 0, 1002, chữ) | Báo lỗi phân trang | [ ] |
| 10.10 | Sao kê không chứa cấp tiền ADMIN, phí, khoản chi CSV | Đúng: chỉ có chuyển tiền | [ ] |
| 10.11 | Chọn tài khoản phụ (nếu giao diện cho chọn) | Chỉ giao dịch của tài khoản đó | [ ] |

## 11. Quản trị — đối soát

| # | Bước | Kết quả mong đợi | Đạt |
|---|---|---|---|
| 11.1 | ADMIN → **Đối soát số dư → Kiểm tra** sau các mục trên | **0 sai lệch**, số ví đã kiểm tra = số ví có trong hệ thống | [ ] |
| 11.2 | Gây sai lệch có chủ đích (SQL, chỉ làm trên DB thử nghiệm) rồi kiểm tra lại | Ví bị lệch xuất hiện với số dư thực tế, số dư theo sổ cái và chênh lệch đúng +1 | [ ] |
| 11.3 | Hoàn tác sai lệch rồi kiểm tra lại | Về 0 sai lệch | [ ] |
| 11.4 | USER gọi API đối soát | Bị từ chối (403) | [ ] |

```sql
-- 11.2 (cố ý làm lệch +1 cho ví của An), và 11.3 (hoàn tác)
UPDATE wallets SET balance_dong = balance_dong + 1 WHERE wallet_code = '<mã-ví-An>';
UPDATE wallets SET balance_dong = balance_dong - 1 WHERE wallet_code = '<mã-ví-An>';
```

## 12. Phân quyền và bảo mật cơ bản

| # | Bước | Kết quả mong đợi | Đạt |
|---|---|---|---|
| 12.1 | Mở trực tiếp `http://localhost:8081/api/v1/me/wallet` trên tab chưa đăng nhập | `401` kèm JSON lỗi, không có stack trace | [ ] |
| 12.2 | Dùng token của USER gọi `POST /api/v1/admin/grants` (Postman/curl) | `403 FORBIDDEN` | [ ] |
| 12.3 | Đăng ký với JSON có thêm `"role":"ADMIN"` (curl) | Tài khoản vẫn là `USER` | [ ] |
| 12.4 | Nhập `<script>alert(1)</script>` vào Tên hiển thị, Lý do cấp tiền, Nội dung chi tiêu | Hiển thị là chữ thường, **không** chạy script | [ ] |
| 12.5 | Nhập `' OR 1=1 --` vào email/ô tìm mã ví | Báo lỗi bình thường, không lỗi 500 | [ ] |
| 12.6 | Xem tab Network khi có lỗi | Thông báo lỗi chỉ có `code`, `message`, `traceId` | [ ] |

```powershell
# Ví dụ curl cho 12.2 (thay <TOKEN> bằng token của USER lấy từ /auth/login)
curl -s -X POST http://localhost:8081/api/v1/admin/grants -H "Authorization: Bearer <TOKEN>" -H "Content-Type: application/json" -d "{\"requestKey\":\"11111111-1111-1111-1111-111111111111\",\"recipientWalletCode\":\"WLT00000000000000000000\",\"amountDong\":1,\"reason\":\"x\"}"
```

## 13. Giao diện, truy cập, tương thích

| # | Bước | Kết quả mong đợi | Đạt |
|---|---|---|---|
| 13.1 | Bấm nút mặt trăng/mặt trời | Đổi sáng/tối; tải lại vẫn nhớ lựa chọn; **biểu đồ** đổi màu theo chế độ | [ ] |
| 13.2 | Thu nhỏ cửa sổ về ~360 px (DevTools thiết bị di động) | Menu chuyển xuống thanh dưới, vuốt ngang được; không có cuộn ngang cả trang; bảng/biểu đồ không bị tràn | [ ] |
| 13.3 | Chỉ dùng bàn phím (Tab, Shift+Tab, Enter, Esc) | Mọi nút/ô nhập truy cập được; **viền focus** thấy rõ; hộp xác nhận bắt focus và `Esc` đóng | [ ] |
| 13.4 | Bật "giảm chuyển động" của hệ điều hành | Hiệu ứng chuyển động tắt | [ ] |
| 13.5 | Số tiền lớn (1.000.000.000.000) | Định dạng có dấu chấm nhóm, không vỡ bố cục | [ ] |
| 13.6 | Thử trên Chrome và Edge (và Firefox nếu có) | Hành vi giống nhau | [ ] |
| 13.7 | Chữ có dấu tiếng Việt (tên, nội dung, lý do) | Hiển thị đúng ở UI, email Mailpit, CSV, PDF | [ ] |

## 14. Vận hành

| # | Bước | Kết quả mong đợi | Đạt |
|---|---|---|---|
| 14.1 | `docker compose restart api` rồi đăng nhập lại | Dữ liệu còn nguyên (số dư, lịch sử, thông báo) | [ ] |
| 14.2 | `docker compose down` (không `-v`) rồi `up -d` | Dữ liệu còn nguyên | [ ] |
| 14.3 | `http://localhost:8081/actuator/health` | `UP`, không lộ thông tin nhạy cảm | [ ] |
| 14.4 | Dừng `db` (`docker compose stop db`), mở ứng dụng | Báo lỗi thân thiện (không stack trace); bật lại `db` thì hoạt động tiếp | [ ] |

> **Cảnh báo dữ liệu:** các lệnh SQL ở trên và mọi thao tác trong tài liệu này chạy trên DB của Docker Compose. Không chạy trên dữ liệu thật. Không dùng `docker compose down -v` nếu muốn giữ dữ liệu.

---

## Mẫu ghi nhận lỗi

```
Mã mục: (vd 5.5)
Bước đã làm:
Kết quả mong đợi:
Kết quả thực tế:
traceId (nếu có):
Ảnh chụp/Ghi chú:
```
