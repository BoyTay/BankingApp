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

## Sau mốc 2

`GET /statements?from=&to=&format=csv|pdf`, `POST /expense-imports?format=SAMPLE_A|SAMPLE_B`, và `GET /expense-stats?groupBy=category|month` thuộc mốc 3. Hợp đồng chi tiết của chúng sẽ được chốt cùng chức năng.
