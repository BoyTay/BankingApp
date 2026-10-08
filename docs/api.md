# REST API v1 — hợp đồng cho giao diện web

Base URL `http://localhost:8080/api/v1` khi phát triển; dùng HTTPS khi chạy qua mạng. JSON UTF-8. Mọi số tiền là **số nguyên VND** kiểu JSON integer, không dùng số thực hoặc chuỗi. `amountDong` hợp lệ trong `1..1000000000000`; số dư tối đa `9223372036854775807`. Thời gian ISO-8601 UTC. UUID và mã ví là chuỗi; mã ví do server sinh theo dạng `WLT` + 20 ký tự hex. Trường bắt buộc thiếu/sai kiểu trả `400 INVALID_REQUEST`, riêng số tiền sai kiểu/giới hạn trả `400 INVALID_AMOUNT`.

Các endpoint cần đăng nhập dùng `Authorization: Bearer <accessToken>`. Token sống 8 giờ, đăng xuất thu hồi ngay. Giao diện web giữ token trong bộ nhớ của tab và xóa khi đăng xuất hoặc tải lại trang. Mọi lỗi trả cùng cấu trúc:

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

Đăng nhập bị giới hạn 10 lần mỗi 60 giây theo địa chỉ kết nối và email; đăng ký bị giới hạn 20 lần mỗi 60 giây theo địa chỉ kết nối. Khi vượt giới hạn, API trả `429 RATE_LIMITED` kèm `Retry-After` (giây). Đăng nhập thành công xóa bộ đếm tương ứng. Xem [hướng dẫn vận hành](operations.md).

### `POST /auth/logout` — USER/ADMIN

Không có body. Response `204` không có body. Gọi lại bằng token đã thu hồi trả `401 UNAUTHORIZED`.

### `GET /me/wallet` — USER/ADMIN

Response `200`: `{"walletId":"759d6c81-fd08-46c1-8597-85ddd790bf7c","walletCode":"WLT759D6C81FD0846C18597","balanceDong":0}`.

Endpoint tương thích giao diện hiện tại, luôn trả tài khoản Thanh toán mặc định.

### `GET /me/accounts`, `GET /me/accounts/{id}` — USER/ADMIN

Liệt kê hoặc xem một tài khoản thuộc người gọi. Mỗi phần tử có `accountId`, `accountCode`, `accountType`, `status`, `isDefault`, `balanceDong`. Tài khoản của người khác trả `404 ACCOUNT_NOT_FOUND`.

### `POST /me/accounts` — USER/ADMIN

Mở thêm Thanh toán: `{"requestKey":"d287954d-fb6d-44cd-a9c6-3d99d2141178","type":"CHECKING"}`. Tài khoản mới có số dư 0, không thay tài khoản mặc định. Mở mới trả `201`, gửi lại cùng `requestKey` và nội dung trả `200` với cùng tài khoản. Thiếu trường trả `400 INVALID_REQUEST`; dùng lại khóa cho loại/nội dung khác trả `409 ACCOUNT_KEY_CONFLICT`.

Mở Tiết kiệm: `{"requestKey":"d287954d-fb6d-44cd-a9c6-3d99d2141178","type":"SAVINGS","fundingAccountId":"759d6c81-fd08-46c1-8597-85ddd790bf7c","amountDong":200000}`. `fundingAccountId` phải là Thanh toán đang hoạt động của chính người gọi; tiền gửi tối thiểu 100.000 VND và được trừ, ghi bút toán cùng lúc tạo Tiết kiệm. Kỳ hạn minh họa 90 ngày, lãi suất năm 400 điểm cơ bản (4,00%); server cố định kỳ hạn/lãi suất vào khoản gửi lúc mở. Cấu hình sai trả `503 SAVINGS_PRODUCT_UNAVAILABLE` cho việc mở Tiết kiệm nhưng không chặn đăng nhập hoặc Thanh toán. Tín dụng chưa được mở qua API và trả `400 ACCOUNT_TYPE_UNAVAILABLE`.

### `GET /me/accounts/{id}/fees` — USER/ADMIN

Liệt kê phí của tài khoản thuộc người gọi, mới nhất trước. Mỗi khoản có `feeId`, `feeCode`, `periodStart`, `amountDong`, `status` (`DUE` hoặc `PAID`) và `paidAt`. Tài khoản của người khác trả `404 ACCOUNT_NOT_FOUND`.

### `GET /me/accounts/{id}/savings`, `POST /me/accounts/{id}/savings/withdraw` — USER/ADMIN

`GET` trả tiền gốc, kỳ hạn, lãi suất cố định, ngày đáo hạn UTC, tài khoản Thanh toán nguồn, trạng thái và số dư. Chỉ chủ tài khoản được xem.

`POST` nhận `{"requestKey":"d287954d-fb6d-44cd-a9c6-3d99d2141178"}` và **rút toàn bộ** về đúng tài khoản Thanh toán đã dùng khi mở. Trước ngày đáo hạn, không có lãi và phí là 0,5% tiền gốc, làm tròn tới VND gần nhất. Từ ngày đáo hạn, không mất phí; lãi = `tiền gốc × lãi suất năm (bps) × số ngày kỳ hạn / (10000 × 365)`, làm tròn tới VND gần nhất và chỉ được ghi lúc tất toán. Rút sau ngày đáo hạn không tăng thêm lãi. Phí/lãi có bút toán riêng, khoản chuyển về được ghi như một giao dịch giữa hai tài khoản; Tiết kiệm trở thành `CLOSED` với số dư 0. Rút mới trả `201`, thử lại cùng khóa trả `200` và cùng biên nhận; khóa khác sau khi tất toán trả `409 ACCOUNT_CLOSED`. Tài khoản không thuộc người gọi trả `404 ACCOUNT_NOT_FOUND`.

### `GET /wallets/lookup/{walletCode}` — USER/ADMIN

Response `200`: `{"walletId":"9b292dd2-313f-44de-ac18-12419025d450","walletCode":"WLT9B292DD2313F44DEAC18","displayName":"Tran Binh"}`. Không trả email hoặc số dư người nhận. Lỗi: `404 WALLET_NOT_FOUND`.

## Chuyển tiền và lịch sử

### `POST /transfers` — USER/ADMIN

UI sinh một `requestKey` UUID khi người dùng xác nhận; retry do timeout phải giữ nguyên khóa và payload. Request:

```json
{"requestKey":"d287954d-fb6d-44cd-a9c6-3d99d2141178","recipientWalletCode":"WLT9B292DD2313F44DEAC18","amountDong":125001}
```

Giao dịch mới trả `201`; retry giống hệt trả `200` với **cùng body** biên nhận. Có thể truyền thêm `sourceAccountId` để chọn tài khoản Thanh toán nguồn thuộc người gọi; bỏ qua trường này thì dùng tài khoản mặc định. Response:

```json
{"transferId":"6bc7b1af-3e67-4c8c-bbb7-8edf74543045","requestKey":"d287954d-fb6d-44cd-a9c6-3d99d2141178","senderWalletCode":"WLT759D6C81FD0846C18597","recipientWalletCode":"WLT9B292DD2313F44DEAC18","amountDong":125001,"direction":"OUTGOING","myBalanceAfterDong":874999,"createdAt":"2026-10-07T05:00:00Z"}
```

Lỗi: `400 INVALID_REQUEST`, `400 INVALID_AMOUNT`, `400 SELF_TRANSFER`, `404 WALLET_NOT_FOUND`, `409 IDEMPOTENCY_CONFLICT` (cùng khóa, người nhận/số tiền khác), `422 INSUFFICIENT_FUNDS`, `422 BALANCE_OVERFLOW`. Hai request đồng thời cùng khóa và payload phải cho một giao dịch; nếu yêu cầu đầu thất bại thì không giữ khóa.

### `GET /transfers/{id}` — USER/ADMIN

Response `200`: cùng schema biên nhận ở trên. `direction` và `myBalanceAfterDong` luôn theo ví của người gọi; người nhận thấy `INCOMING` và số dư của **mình**, không thấy số dư ví nguồn. Không có `senderBalanceAfterDong`/`recipientBalanceAfterDong` trong bất kỳ response nào. Chỉ người gửi hoặc người nhận được xem, kể cả ADMIN không tham gia cũng không được xem. Giao dịch không tồn tại hoặc không có quyền xem đều trả `404 TRANSFER_NOT_FOUND` để tránh lộ thông tin.

### `GET /transfers?page=0&size=20` — USER/ADMIN

`page` từ 0; `size` từ 1 đến 100, mặc định 20. Có thể thêm `accountId` để xem lịch sử một tài khoản thuộc người gọi; thiếu thì dùng tài khoản mặc định. Sắp xếp `createdAt DESC, transferId DESC`. Chỉ giao dịch mà tài khoản đã chọn là nguồn hoặc đích. Response `200`:

```json
{"items":[{"transferId":"6bc7b1af-3e67-4c8c-bbb7-8edf74543045","requestKey":"d287954d-fb6d-44cd-a9c6-3d99d2141178","senderWalletCode":"WLT759D6C81FD0846C18597","recipientWalletCode":"WLT9B292DD2313F44DEAC18","amountDong":125001,"direction":"OUTGOING","myBalanceAfterDong":874999,"createdAt":"2026-10-07T05:00:00Z"}],"page":0,"size":20,"totalItems":1}
```

Lỗi: `400 INVALID_REQUEST` cho phân trang sai.

## Quản trị

### `POST /admin/grants` — ADMIN

Request: `{"requestKey":"55e97fbc-d73d-4748-9808-c68bf34c225a","recipientWalletCode":"WLT759D6C81FD0846C18597","amountDong":1000000,"reason":"So du demo dot 1"}`. UI sinh UUID khi ADMIN xác nhận và giữ nguyên khóa **cùng toàn bộ payload** khi thử lại sau timeout. Lý do 1–500 ký tự; tiền `1..1000000000000`. Cấp mới trả `201`; cùng ADMIN, cùng `requestKey` và cùng nội dung trả `200` với **đúng body biên nhận cũ**:

```json
{"grantId":"3465a4a7-5c42-46da-a905-dd53608547a3","requestKey":"55e97fbc-d73d-4748-9808-c68bf34c225a","adminUserId":"69a8e72e-6d31-4db0-a55e-96914f2c7da2","recipientWalletCode":"WLT759D6C81FD0846C18597","amountDong":1000000,"balanceAfterDong":1000000,"reason":"So du demo dot 1","createdAt":"2026-10-07T04:00:00Z"}
```

Lỗi: `400 INVALID_REQUEST` (thiếu/sai UUID hoặc lý do), `400 INVALID_AMOUNT`, `403 FORBIDDEN`, `404 WALLET_NOT_FOUND`, `409 GRANT_KEY_CONFLICT` (cùng ADMIN và khóa nhưng khác mã ví, tiền hoặc lý do), `422 BALANCE_OVERFLOW`. Cấp tiền và bút toán audit cùng transaction. Hai request đồng thời cùng ADMIN/khóa chỉ tạo một khoản cấp; yêu cầu thất bại không giữ khóa. Khóa chống trùng có phạm vi từng ADMIN. UI không tự gửi lại; nút thử lại yêu cầu cũ chỉ hiện khi kết quả chưa chắc chắn và gửi đúng payload đã xác nhận.

### `GET /admin/reconciliation?page=0&size=20` — ADMIN

Đối chiếu `wallets.balance_dong` với tổng `ledger_entries.delta_dong` của từng ví. Tài khoản mới bắt đầu với 0 VND. Endpoint **chỉ đọc**, không sửa ví hoặc bút toán. `page` bắt đầu từ 0, `size` từ 1 đến 100; chỉ phân trang các ví sai lệch, sắp theo mã ví. Response `200`:

```json
{"checkedAt":"2026-10-07T10:00:00Z","checkedWallets":3,"mismatchCount":1,"page":0,"size":20,"items":[{"walletId":"759d6c81-fd08-46c1-8597-85ddd790bf7c","walletCode":"WLT759D6C81FD0846C18597","actualBalanceDong":"100005","ledgerBalanceDong":"100000","differenceDong":"5"}]}
```

Ba giá trị tiền là chuỗi số nguyên chính xác; `differenceDong = actualBalanceDong - ledgerBalanceDong`. Khi không có sai lệch, `mismatchCount=0` và `items=[]`. Kết quả là ảnh chụp nhất quán tại thời điểm `checkedAt`; lần gọi sau có thể thay đổi nếu phát sinh giao dịch. Phép kiểm tra này phát hiện sai lệch **tổng số dư**, chưa xác minh từng cặp bút toán của một giao dịch. Lỗi: `400 INVALID_REQUEST` cho phân trang sai, `401 UNAUTHORIZED`, `403 FORBIDDEN`.

## Sao kê, nhập chi tiêu và thống kê — mốc 3

Mọi endpoint dưới đây yêu cầu `Authorization: Bearer <accessToken>` và chỉ truy cập dữ liệu của người gọi, kể cả khi người gọi là ADMIN. Ngày truyền theo `yyyy-MM-dd` UTC; khoảng `from..to` **bao gồm cả hai ngày**, `from <= to` và tối đa 366 ngày. Không có tham số ngày mặc định; thiếu hoặc sai ngày trả `400 INVALID_DATE_RANGE`.

### `GET /statements?from=2026-01-01&to=2026-01-31&format=csv|pdf`

Response `200` là **tệp nhị phân**, không phải JSON. Có thể thêm `accountId` để xuất sao kê một tài khoản thuộc người gọi; thiếu thì dùng tài khoản mặc định. Chỉ liệt kê các `transfers` của tài khoản đã chọn trong khoảng ngày; cấp tiền quản trị và chi tiêu CSV không nằm trong sao kê chuyển tiền. Dòng giao dịch có thời điểm UTC, mã giao dịch, chiều `OUTGOING`/`INCOMING`, mã ví đối ứng, số tiền VND nguyên đồng và **số dư sau của chính tài khoản người gọi**. Kể cả PDF/CSV cũng không chứa số dư của tài khoản đối ứng. Tối đa 10.000 giao dịch trong một lần xuất; vượt mức trả `422 STATEMENT_TOO_LARGE`.

- `format=csv`: `Content-Type: text/csv; charset=UTF-8`; `Content-Disposition: attachment; filename="statement-20260101-20260131.csv"`. Nội dung UTF-8 có BOM để Excel Windows nhận tiếng Việt; header là `Thời gian UTC,Mã giao dịch,Loại,Ví đối ứng,Số tiền (VND),Số dư sau (VND)`.
- `format=pdf`: `Content-Type: application/pdf`; `Content-Disposition: attachment; filename="statement-20260101-20260131.pdf"`. PDF nhúng font Unicode và có tiêu đề, mã ví, khoảng ngày, các dòng giao dịch cùng tổng tiền vào/ra.

Lỗi: `400 INVALID_DATE_RANGE`, `400 INVALID_FORMAT`, `422 STATEMENT_TOO_LARGE`, `401 UNAUTHORIZED`.

Với tập giao dịch lớn, truyền **cả hai** tham số `page` (bắt đầu từ 0, tối đa 1000) và `size` (`1..1000`), ví dụ `&page=0&size=500`. API xuất đúng phần được chọn theo thứ tự thời gian và mã giao dịch; response có `X-Page`, `X-Page-Size`, `X-Has-More`. Khi `X-Has-More=true`, tăng `page` để tải phần tiếp theo. Tên tệp thêm `-page-N`; tổng nhận/chuyển trong PDF là tổng **của phần đó**. Không truyền hai tham số này thì cách xuất cũ và giới hạn 10.000 giao dịch giữ nguyên. Tham số sai trả `400 INVALID_PAGE`.

### `POST /expense-imports/preview?format=SAMPLE_A|SAMPLE_B`

Giao diện web chỉ cung cấp [một tệp mẫu tiếng Việt](../samples/chi-tieu-mau.csv) và gửi `format=SAMPLE_B`. Mẫu mới dùng dấu phẩy, header `Ngày,Nội dung,Danh mục,Số tiền`, ngày `yyyy-MM-dd` và số tiền nguyên VND. `SAMPLE_A` cùng mẫu B dấu chấm phẩy cũ vẫn được API chấp nhận để tương thích với tệp đã có.

Gửi `multipart/form-data` với phần `file` (CSV UTF-8, tối đa 1 MiB). Cần Bearer token; endpoint chỉ phân tích bằng Adapter tương ứng và **không ghi database**. Response `200` trả toàn bộ dòng hợp lệ và lỗi theo dòng để người dùng sửa tệp trước khi xác nhận:

```json
{"format":"SAMPLE_A","sourceName":"expenses.csv","rowCount":3,"validRows":[{"sourceRow":2,"spentOn":"2026-01-01","description":"Lunch","category":"Food","amountDong":12000}],"errors":[{"sourceRow":3,"code":"INVALID_CSV","message":"Dòng 3: ngày không hợp lệ"},{"sourceRow":4,"code":"INVALID_AMOUNT","message":"Dòng 4: số tiền không hợp lệ"}],"canImport":false}
```

Lỗi toàn tệp (UTF-8, header, cấu trúc, quá 5.000 dòng) nằm trong `errors` với `sourceRow` bằng `1` hoặc `null`. Chỉ bật xác nhận khi `canImport=true`. Chọn tệp hoặc định dạng khác phải xem trước lại. Lỗi tải lên quá 1 MiB trả `413 FILE_TOO_LARGE`.

### `POST /expense-imports?format=SAMPLE_A|SAMPLE_B`

`Content-Type: multipart/form-data`; hai phần bắt buộc: `requestKey` là UUID và `file` là tệp CSV UTF-8. `requestKey` đại diện một lần nhập; retry cùng khóa + cùng nội dung trả batch cũ, cùng khóa + nội dung khác trả `409 IMPORT_KEY_CONFLICT`. Cùng tệp và cùng format được nhập lại bằng khóa khác cũng trả batch cũ, tránh đếm chi tiêu hai lần. Phạm vi chống trùng là **từng người dùng**.

Giao diện chỉ gọi endpoint này sau khi người dùng xem trước và bấm xác nhận. Nếu timeout hoặc lỗi 5xx khiến kết quả chưa rõ, nút thử lại gửi **cùng `requestKey`, format và tệp**; không tạo khóa mới.

Tối đa 1 MiB và 5.000 dòng dữ liệu; tệp rỗng, header sai, UTF-8 lỗi hoặc một dòng sai đều từ chối **cả batch**, không lưu dòng nào. Ngày chi tiêu phải thuộc `2000-01-01..2100-12-31`; số tiền là VND dương `1..1000000000000`, không làm tròn. Format `SAMPLE_A` dùng header `date,description,category,amount_vnd`, ngày `yyyy-MM-dd`, tiền số nguyên hoặc phần thập phân đúng `.00`. Format `SAMPLE_B` nhận mẫu mới dấu phẩy nêu trên; tệp cũ có dấu `;`, header `Ngày GD;Nội dung;Nhóm;Số tiền`, ngày `dd/MM/yyyy`, tiền phân nhóm bằng dấu chấm và tùy chọn `,00` vẫn đọc được. Nội dung/mô tả 1–500 ký tự, danh mục 1–100 ký tự.

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
# API tài khoản Tín dụng mô phỏng

Tất cả đường dẫn dưới đây nằm dưới `/api/v1` và cần Bearer token. Các lệnh `POST` dùng UUID `requestKey`; thử lại cùng khóa và nội dung trả `200`, tạo mới trả `201`.

- `POST /me/accounts` với `{ "requestKey": "UUID", "type": "CREDIT" }`: mở tài khoản hạn mức 0 VND.
- `GET /me/accounts/{id}/credit`: hạn mức, dư nợ và hạn mức còn dùng.
- `GET /me/accounts/{id}/credit/activity`: tối đa 100 khoản sử dụng, hoàn trả và phí gần nhất.
- `POST /me/accounts/{id}/credit/charges` với `requestKey`, `amountDong`, `description`: ghi khoản sử dụng hạn mức.
- `POST /me/accounts/{id}/credit/repayments` với `requestKey`, `sourceAccountId` (Thanh toán của chính chủ), `amountDong`: hoàn trả dư nợ.
- `POST /admin/credit-accounts/{id}/limit` với `requestKey`, `limitDong`: ADMIN đặt hạn mức; không được thấp hơn dư nợ.
- `POST /me/accounts/{id}/close` với `requestKey`: đóng Thanh toán phụ hoặc Tín dụng đã tất toán và hết phí đến hạn. Không xóa lịch sử.

Phí thường niên 20.000 VND được ghi thành dư nợ khi đủ hạn mức. Nếu không đủ, khoản phí giữ trạng thái `DUE` và có thể xem ở `GET /me/accounts/{id}/fees`.
