# Các pattern GoF trong ví nội bộ

Ba pattern dưới đây nằm trong các API đang chạy ở mốc 3. Tất cả endpoint dùng `AuthInterceptor`; service chỉ đọc/ghi dữ liệu thuộc `principal.userId()`.

## 1. Factory Method — xuất sao kê

**Định nghĩa:** Creator khai báo factory method trả Product; Concrete Creator quyết định Concrete Product cụ thể. Logic gọi xuất nằm ở Creator, không chứa nhánh định dạng trong từng màn hình.

```mermaid
classDiagram
  class StatementExporterCreator {
    <<abstract>>
    +export(Statement) byte[]
    #createExporter() StatementExporter
  }
  class CsvStatementCreator
  class PdfStatementCreator
  class StatementExporter {
    <<interface>>
    +write(Statement, OutputStream)
  }
  class CsvStatementExporter
  class PdfStatementExporter
  StatementExporterCreator <|-- CsvStatementCreator
  StatementExporterCreator <|-- PdfStatementCreator
  StatementExporterCreator --> StatementExporter : createExporter()
  StatementExporter <|.. CsvStatementExporter
  StatementExporter <|.. PdfStatementExporter
```

**Luồng demo:** `GET /api/v1/statements?from=2026-01-01&to=2026-12-31&format=csv` (hoặc `pdf`) → `StatementController.export()` → `StatementService.export()` truy vấn chuyển tiền của ví hiện tại → chọn `CsvStatementCreator`/`PdfStatementCreator` → quy trình chung `StatementExporterCreator.export()` gọi factory method `createExporter()` → `CsvStatementExporter.write()`/`PdfStatementExporter.write()`. Product nhận cùng `Statement`, kết quả là tệp CSV UTF-8 hoặc PDF có font Unicode. Chỉ số dư sau của ví đăng nhập được đưa vào tệp.

**Vì sao cần:** Hai định dạng có cách ghi khác nhau nhưng dùng cùng quá trình tạo tệp và dữ liệu sao kê. **Hỏi đáp:** “Factory Method khác simple factory?” — mỗi Concrete Creator ghi đè factory method; quy trình `export()` ở Creator không phải `switch` tạo Product. `StatementService` chọn Creator theo tham số API, còn Creator tạo Product.

## 2. Adapter — nhập CSV chi tiêu

**Định nghĩa:** Adapter chuyển giao diện nguồn không tương thích thành giao diện đích mà phần nghiệp vụ mong đợi.

```mermaid
classDiagram
  class ExpenseCsvAdapter {
    <<interface>>
    +preview(byte[]) CsvPreview
    +read(byte[]) List~ImportedExpense~
  }
  class SampleFormatACsvAdapter
  class SampleFormatBCsvAdapter
  class CsvPreview
  class ImportedExpense
  ExpenseCsvAdapter <|.. SampleFormatACsvAdapter
  ExpenseCsvAdapter <|.. SampleFormatBCsvAdapter
  SampleFormatACsvAdapter --> CsvPreview
  SampleFormatBCsvAdapter --> CsvPreview
  CsvPreview --> ImportedExpense
```

**Định dạng mẫu A:** `date,description,category,amount_vnd` (ngày `yyyy-MM-dd`, số tiền nguyên hoặc `.00`; xem `samples/expenses-a.csv`). **Định dạng mẫu B:** tệp CSV tiếng Việt mới dùng `Ngày,Nội dung,Danh mục,Số tiền` và dấu phẩy (xem [tệp mẫu chuẩn](../samples/chi-tieu-mau.csv)); Adapter B cũng đọc tệp cũ có `Ngày GD;Nội dung;Nhóm;Số tiền` và dấu chấm phẩy (xem `samples/expenses-b.csv`). Đây là các cấu trúc tự tạo để demo, không gắn với ngân hàng thực tế. Giao diện web chỉ hiển thị tệp mẫu chuẩn; định dạng A còn ở API để đọc tệp cũ và minh họa Adapter.

**Luồng demo:** `POST /api/v1/expense-imports/preview?format=SAMPLE_A|SAMPLE_B` → Adapter tương ứng → `ExpenseCsvParsing.preview()` → dòng hợp lệ và lỗi theo dòng, chưa ghi database. Sau khi người dùng xác nhận, `POST /api/v1/expense-imports?format=...` với cùng tệp và `requestKey` → `ExpenseImportService.importFile()` → Adapter `read()` dùng cùng bộ phân tích → `import_batches` và `imported_expenses`. Dedupe theo người dùng + UUID hoặc hash nội dung. **Vì sao cần:** phần lưu và thống kê chỉ hiểu một mô hình dữ liệu, không cần biết tên cột và cú pháp từng CSV. **Hỏi đáp:** “Adapter có làm đổi số dư?” — không; service chỉ ghi bảng nhập, không cập nhật `wallets` hoặc `ledger_entries`.

## 3. Strategy — tổng hợp thống kê

**Định nghĩa:** đóng gói các thuật toán có cùng giao diện để chọn cách xử lý khi chạy.

```mermaid
classDiagram
  class ExpenseAggregationStrategy {
    <<interface>>
    +sqlKeyExpression() String
    +aggregate(List~ImportedExpense~) List~ExpenseSummary~
  }
  class ByCategoryStrategy
  class ByMonthStrategy
  class ExpenseStatisticsService
  ExpenseAggregationStrategy <|.. ByCategoryStrategy
  ExpenseAggregationStrategy <|.. ByMonthStrategy
  ExpenseStatisticsService --> ExpenseAggregationStrategy
```

**Luồng demo:** `GET /api/v1/expense-stats?from=2026-01-01&to=2026-12-31&groupBy=category` (hoặc `month`) → `ExpenseStatsController.view()` → `ExpenseStatisticsService.stats()` chọn `ByCategoryStrategy`/`ByMonthStrategy` → Strategy cung cấp biểu thức nhóm SQL cố định → PostgreSQL tính `SUM/COUNT` → Java sắp xếp key theo cùng thứ tự Unicode như trước. `aggregate()` giữ phép nhóm trong bộ nhớ làm tham chiếu kiểm thử tương đương. **Vì sao cần:** hai cách nhóm có thể thay thế qua giao diện; database chỉ trả các nhóm, không truyền mọi dòng chi tiêu về API. **Hỏi đáp:** “Đổi Strategy lúc nào?” — theo `groupBy` của request; kết quả không phụ thuộc định dạng CSV nguồn.

## Mở rộng: Factory Method và Policy cho tài khoản

`AccountFactory.create()` giữ các bước chung: sinh ID, mã tài khoản và tháng bắt đầu tính phí. `CheckingAccountFactory.build()` và `SavingsAccountFactory.build()` tạo cấu hình theo loại; factory cho Tín dụng sẽ được thêm ở chặng sau. `AccountService` chọn factory theo `type`; `SavingsService` bổ sung tiền gốc, kỳ hạn và lãi suất cố định cho khoản gửi.

`AccountPolicy` là giao diện quy tắc theo loại tài khoản. `TransferService` hỏi policy của cả nguồn và đích trước khi chuyển; `GrantService` hỏi policy của đích trước khi cấp tiền. `CheckingAccountPolicy` cho phép ba thao tác này, còn `SavingsAccountPolicy` từ chối để tiền gửi chỉ đi qua quy trình mở/tất toán có phí và lãi rõ ràng. Tín dụng chưa có factory/policy nên chưa thể mở hoặc dùng để giao dịch.

## 4. Observer — thông báo và nhắc nhở

**Định nghĩa:** Subject giữ danh sách Observer và báo cho tất cả khi có sự kiện; Subject không biết Observer làm gì với sự kiện.

```mermaid
classDiagram
  class EventSubject {
    +attach(NotificationObserver)
    +detach(NotificationObserver)
    +publish(WalletEvent)
    +deliver(WalletEvent)
  }
  class NotificationObserver {
    <<interface>>
    +update(WalletEvent)
  }
  class InAppObserver
  class EmailObserver
  class SmsObserver
  class WalletEvent {
    <<sealed interface>>
  }
  EventSubject o-- NotificationObserver
  NotificationObserver <|.. InAppObserver
  NotificationObserver <|.. EmailObserver
  NotificationObserver <|.. SmsObserver
  EventSubject ..> WalletEvent
  TransferService --> EventSubject : publish
  GrantService --> EventSubject : publish
  LowBalanceMonitor --> EventSubject : deliver
  ReminderService --> EventSubject : publish
```

**Luồng demo:** `POST /api/v1/transfers` → `TransferService.transfer()` ghi sổ cái rồi `events.publish(TransferSent/TransferReceived)` → giao dịch commit → `EventSubject` gọi từng Observer: `InAppObserver` ghi bảng `notifications` (hiện ở tab **Thông báo**), `EmailObserver` gửi SMTP tới Mailpit (`http://localhost:8025`), `SmsObserver` ghi log mô phỏng. Cùng cơ chế phục vụ: cấp tiền, cảnh báo số dư thấp (`LowBalanceMonitor`) và nhắc nhở hằng ngày (`ReminderService`: phí chưa thu, dư nợ tín dụng, tiết kiệm sắp đáo hạn trong 3 ngày).

**Vì sao cần:** thêm kênh mới (ví dụ push) chỉ cần thêm một Observer; `TransferService` không biết có bao nhiêu kênh. **Điểm cần nhớ:** sự kiện chỉ phát **sau khi commit**, nên giao dịch rollback không gửi thông báo; Observer lỗi được bắt riêng, không làm hỏng chuyển tiền hay Observer khác. Gọi lại cùng `requestKey` (idempotent) không phát sự kiện lần hai. Cảnh báo số dư thấp chỉ báo một lần khi số dư tụt xuống dưới ngưỡng và chỉ báo lại sau khi số dư trở lại từ ngưỡng trở lên. **Hỏi đáp:** “Observer khác Spring event?” — ở đây Subject và Observer tự cài đặt để thấy rõ cấu trúc pattern; Spring chỉ tiêm danh sách Observer khi khởi động.
