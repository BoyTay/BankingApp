# REST API v1 — hợp đồng cho JavaFX

Base URL `http://localhost:8080/api/v1` khi phát triển; dùng HTTPS khi chạy qua mạng. JSON UTF-8. Mọi số tiền là **số nguyên VND** kiểu JSON integer, không dùng số thực hoặc chuỗi. `amountDong` hợp lệ trong `1..1000000000000`; số dư tối đa `9223372036854775807`. Thời gian ISO-8601 UTC. UUID và mã ví là chuỗi; mã ví do server sinh theo dạng `WLT` + 20 ký tự hex. Trường bắt buộc thiếu/sai kiểu trả `400 INVALID_REQUEST`, riêng số tiền sai kiểu/giới hạn trả `400 INVALID_AMOUNT`.

Các endpoint cần đăng nhập dùng `Authorization: Bearer <accessToken>`. Token sống 8 giờ, đăng xuất thu hồi ngay. Desktop giữ token trong bộ nhớ. Mọi lỗi trả cùng cấu trúc:

```json
{"code":"INSUFFICIENT_FUNDS","message":"Số dư không đủ","traceId":"6a4a9041-c591-470e-a360-dc4f5a1faac8"}
```

`traceId` phục vụ hỗ trợ; UI hiển thị `message`, không hiển thị stack trace. `401` dành cho token thiếu/hết hạn/đã thu hồi; `403` dành cho tài khoản đã xác thực nhưng thiếu quyền.

## Xác thực và ví

### `POST /auth/register` — công khai

Request: `{"email":"an@example.test","displayName":"Nguyen An","password":"StrongPass123!"}`. Email được trim và chuyển chữ thường; mật khẩu 10–128 ký tự; tên 1–120 ký tự. Không nhận trường `role` từ client. Response `201`:

```json
{"userId":"b215fa5c-eb17-4803-8b7b-6a1564142831","email":"an@example.test","displayName":"Nguyen An","role":"USER","wallet":{"walletId":"759d6c81-fd08-46c1-8597-85ddd790bf7c","walletCode":"WLT759D6C81FD0846C18597","balanceDong":0}}
```

Lỗi: `400 INVALID_REQUEST`, `400 INVALID_EMAIL`, `400 WEAK_PASSWORD`, `409 EMAIL_EXISTS`.

### `POST /auth/login` — công khai

Request: `{"email":"an@example.test","password":"StrongPass123!"}`. Response `200`:

```json
{"accessToken":"<opaque-token>","tokenType":"Bearer","expiresAt":"2026-10-07T13:00:00Z","user":{"userId":"b215fa5c-eb17-4803-8b7b-6a1564142831","email":"an@example.test","displayName":"Nguyen An","role":"USER"}}
```

Lỗi: `400 INVALID_REQUEST`, `401 INVALID_CREDENTIALS` (cùng thông điệp cho email/mật khẩu sai).

### `POST /auth/logout` — USER/ADMIN

Không có body. Response `204` không có body. Gọi lại bằng token đã thu hồi trả `401 UNAUTHORIZED`.

### `GET /me/wallet` — USER/ADMIN

Response `200`: `{"walletId":"759d6c81-fd08-46c1-8597-85ddd790bf7c","walletCode":"WLT759D6C81FD0846C18597","balanceDong":0}`.

### `GET /wallets/lookup/{walletCode}` — USER/ADMIN

Response `200`: `{"walletId":"9b292dd2-313f-44de-ac18-12419025d450","walletCode":"WLT9B292DD2313F44DEAC18","displayName":"Tran Binh"}`. Không trả email hoặc số dư người nhận. Lỗi: `404 WALLET_NOT_FOUND`.

## Chuyển tiền và lịch sử

### `POST /transfers` — USER/ADMIN

UI sinh một `requestKey` UUID khi người dùng xác nhận; retry do timeout phải giữ nguyên khóa và payload. Request:

```json
{"requestKey":"d287954d-fb6d-44cd-a9c6-3d99d2141178","recipientWalletCode":"WLT9B292DD2313F44DEAC18","amountDong":125001}
```

Giao dịch mới trả `201`; retry giống hệt trả `200` với **cùng body** biên nhận. Ví nguồn lấy từ token, client không được chọn. Response:

```json
{"transferId":"6bc7b1af-3e67-4c8c-bbb7-8edf74543045","requestKey":"d287954d-fb6d-44cd-a9c6-3d99d2141178","senderWalletCode":"WLT759D6C81FD0846C18597","recipientWalletCode":"WLT9B292DD2313F44DEAC18","amountDong":125001,"direction":"OUTGOING","myBalanceAfterDong":874999,"createdAt":"2026-10-07T05:00:00Z"}
```

Lỗi: `400 INVALID_REQUEST`, `400 INVALID_AMOUNT`, `400 SELF_TRANSFER`, `404 WALLET_NOT_FOUND`, `409 IDEMPOTENCY_CONFLICT` (cùng khóa, người nhận/số tiền khác), `422 INSUFFICIENT_FUNDS`, `422 BALANCE_OVERFLOW`. Hai request đồng thời cùng khóa và payload phải cho một giao dịch; nếu yêu cầu đầu thất bại thì không giữ khóa.

### `GET /transfers/{id}` — USER/ADMIN

Response `200`: cùng schema biên nhận ở trên. `direction` và `myBalanceAfterDong` luôn theo ví của người gọi; người nhận thấy `INCOMING` và số dư của **mình**, không thấy số dư ví nguồn. Không có `senderBalanceAfterDong`/`recipientBalanceAfterDong` trong bất kỳ response nào. Chỉ người gửi hoặc người nhận được xem, kể cả ADMIN không tham gia cũng không được xem. Giao dịch không tồn tại hoặc không có quyền xem đều trả `404 TRANSFER_NOT_FOUND` để tránh lộ thông tin.

### `GET /transfers?page=0&size=20` — USER/ADMIN

`page` từ 0; `size` từ 1 đến 100, mặc định 20. Sắp xếp `createdAt DESC, transferId DESC`. Chỉ giao dịch mà ví hiện tại là nguồn hoặc đích. Response `200`:

```json
{"items":[{"transferId":"6bc7b1af-3e67-4c8c-bbb7-8edf74543045","requestKey":"d287954d-fb6d-44cd-a9c6-3d99d2141178","senderWalletCode":"WLT759D6C81FD0846C18597","recipientWalletCode":"WLT9B292DD2313F44DEAC18","amountDong":125001,"direction":"OUTGOING","myBalanceAfterDong":874999,"createdAt":"2026-10-07T05:00:00Z"}],"page":0,"size":20,"totalItems":1}
```

Lỗi: `400 INVALID_REQUEST` cho phân trang sai.

## Quản trị

### `POST /admin/grants` — ADMIN

Request: `{"recipientWalletCode":"WLT759D6C81FD0846C18597","amountDong":1000000,"reason":"So du demo dot 1"}`. Lý do 1–500 ký tự; tiền `1..1000000000000`. Response `201`:

```json
{"grantId":"3465a4a7-5c42-46da-a905-dd53608547a3","adminUserId":"69a8e72e-6d31-4db0-a55e-96914f2c7da2","recipientWalletCode":"WLT759D6C81FD0846C18597","amountDong":1000000,"balanceAfterDong":1000000,"reason":"So du demo dot 1","createdAt":"2026-10-07T04:00:00Z"}
```

Lỗi: `400 INVALID_REQUEST`, `400 INVALID_AMOUNT`, `403 FORBIDDEN`, `404 WALLET_NOT_FOUND`, `422 BALANCE_OVERFLOW`. Cấp tiền và bút toán audit cùng transaction. Endpoint này không có khóa chống gửi lặp; UI không tự retry sau timeout mà cần kiểm tra audit trước khi gửi lại.

## Sao kê, nhập chi tiêu và thống kê — mốc 3

Mọi endpoint dưới đây yêu cầu `Authorization: Bearer <accessToken>` và chỉ truy cập dữ liệu của người gọi, kể cả khi người gọi là ADMIN. Ngày truyền theo `yyyy-MM-dd` UTC; khoảng `from..to` **bao gồm cả hai ngày**, `from <= to` và tối đa 366 ngày. Không có tham số ngày mặc định; thiếu hoặc sai ngày trả `400 INVALID_DATE_RANGE`.

### `GET /statements?from=2026-01-01&to=2026-01-31&format=csv|pdf`

Response `200` là **tệp nhị phân**, không phải JSON. Chỉ liệt kê các `transfers` của ví người gọi trong khoảng ngày; cấp tiền quản trị và chi tiêu CSV không nằm trong sao kê chuyển tiền. Dòng giao dịch có thời điểm UTC, mã giao dịch, chiều `OUTGOING`/`INCOMING`, mã ví đối ứng, số tiền VND nguyên đồng và **số dư sau của chính ví người gọi**. Kể cả PDF/CSV cũng không chứa số dư của ví đối ứng. Tối đa 10.000 giao dịch trong một lần xuất; vượt mức trả `422 STATEMENT_TOO_LARGE`.

- `format=csv`: `Content-Type: text/csv; charset=UTF-8`; `Content-Disposition: attachment; filename="statement-20260101-20260131.csv"`. Nội dung UTF-8 có BOM để Excel Windows nhận tiếng Việt; header là `Thời gian UTC,Mã giao dịch,Loại,Ví đối ứng,Số tiền (VND),Số dư sau (VND)`.
- `format=pdf`: `Content-Type: application/pdf`; `Content-Disposition: attachment; filename="statement-20260101-20260131.pdf"`. PDF nhúng font Unicode và có tiêu đề, mã ví, khoảng ngày, các dòng giao dịch cùng tổng tiền vào/ra.

Lỗi: `400 INVALID_DATE_RANGE`, `400 INVALID_FORMAT`, `422 STATEMENT_TOO_LARGE`, `401 UNAUTHORIZED`.

### `POST /expense-imports?format=SAMPLE_A|SAMPLE_B`

`Content-Type: multipart/form-data`; hai phần bắt buộc: `requestKey` là UUID và `file` là tệp CSV UTF-8. `requestKey` đại diện một lần nhập; retry cùng khóa + cùng nội dung trả batch cũ, cùng khóa + nội dung khác trả `409 IMPORT_KEY_CONFLICT`. Cùng tệp và cùng format được nhập lại bằng khóa khác cũng trả batch cũ, tránh đếm chi tiêu hai lần. Phạm vi chống trùng là **từng người dùng**.

Tối đa 1 MiB và 5.000 dòng dữ liệu; tệp rỗng, header sai, UTF-8 lỗi hoặc một dòng sai đều từ chối **cả batch**, không lưu dòng nào. Ngày chi tiêu phải thuộc `2000-01-01..2100-12-31`; số tiền là VND dương `1..1000000000000`, không làm tròn. Format `SAMPLE_A` dùng header `date,description,category,amount_vnd`, ngày `yyyy-MM-dd`, tiền số nguyên hoặc phần thập phân đúng `.00`. Format `SAMPLE_B` dùng dấu `;`, header `Ngày GD;Nội dung;Nhóm;Số tiền`, ngày `dd/MM/yyyy`, tiền số nguyên có thể phân nhóm bằng dấu chấm và có thể kết thúc `,00`. Nội dung/mô tả 1–500 ký tự, danh mục 1–100 ký tự.

Response `201` khi mới nhập, `200` khi trả batch cũ; body giống nhau:

```json
{"batchId":"f97e6bdd-cb90-4532-aa47-b05799ee40c2","format":"SAMPLE_A","sourceName":"expenses-a.csv","rowCount":2,"importedAt":"2026-10-07T06:00:00Z"}
```

Lỗi: `400 INVALID_REQUEST` (thiếu file/khóa), `400 INVALID_FORMAT`, `400 INVALID_CSV` (message có số dòng), `400 INVALID_AMOUNT`, `413 FILE_TOO_LARGE`, `409 IMPORT_KEY_CONFLICT`, `401 UNAUTHORIZED`. Nhập CSV **không cập nhật** `wallets` hoặc `ledger_entries`.

### `GET /expense-stats?from=2026-01-01&to=2026-12-31&groupBy=category|month`

Chỉ tổng hợp `imported_expenses` của các batch thuộc người gọi. `groupBy=category` dùng tên danh mục; `groupBy=month` dùng `yyyy-MM`. Tổng tiền là cộng chính xác số nguyên VND. `items` sắp theo `key` tăng dần. Response `200`:

```json
{"groupBy":"category","from":"2026-01-01","to":"2026-12-31","totalAmountDong":57000,"totalCount":2,"items":[{"key":"An uong","amountDong":45000,"count":1},{"key":"Di lai","amountDong":12000,"count":1}]}
```

Ví dụ trên minh họa cấu trúc; response thực sắp xếp `key` theo thứ tự Unicode tăng dần. Tối đa 100.000 khoản chi trong một truy vấn. Lỗi: `400 INVALID_DATE_RANGE`, `400 INVALID_GROUP_BY`, `422 AGGREGATE_OVERFLOW`, `422 STATS_TOO_LARGE`, `401 UNAUTHORIZED`.
