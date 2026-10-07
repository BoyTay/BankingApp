# Ba GoF patterns trong ví nội bộ

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
    +read(byte[]) List~ImportedExpense~
  }
  class SampleFormatACsvAdapter
  class SampleFormatBCsvAdapter
  class ImportedExpense
  ExpenseCsvAdapter <|.. SampleFormatACsvAdapter
  ExpenseCsvAdapter <|.. SampleFormatBCsvAdapter
  SampleFormatACsvAdapter --> ImportedExpense
  SampleFormatBCsvAdapter --> ImportedExpense
```

**Định dạng mẫu A:** `date,description,category,amount_vnd` (ngày `yyyy-MM-dd`, số tiền nguyên hoặc `.00`; xem `samples/expenses-a.csv`). **Định dạng mẫu B:** `Ngày GD;Nội dung;Nhóm;Số tiền` (ngày `dd/MM/yyyy`, số tiền dùng dấu chấm phân nhóm và tùy chọn `,00`; xem `samples/expenses-b.csv`). Đây là hai cấu trúc tự tạo để demo, không gắn với ngân hàng thực tế.

**Luồng demo:** `POST /api/v1/expense-imports?format=SAMPLE_A` hoặc `SAMPLE_B` với multipart `requestKey` và `file` → `ExpenseImportController.upload()` → `ExpenseImportService.importFile()` → `SampleFormatACsvAdapter.read()`/`SampleFormatBCsvAdapter.read()` → `ExpenseCsvParsing.parse()` → cùng mô hình `ImportedExpense` → `import_batches` và `imported_expenses`. Dedupe theo người dùng + UUID hoặc hash nội dung. **Vì sao cần:** phần lưu và thống kê chỉ hiểu một mô hình dữ liệu, không cần biết tên cột và cú pháp từng CSV. **Hỏi đáp:** “Adapter có làm đổi số dư?” — không; service chỉ ghi bảng nhập, không cập nhật `wallets` hoặc `ledger_entries`.

## 3. Strategy — tổng hợp thống kê

**Định nghĩa:** đóng gói các thuật toán có cùng giao diện để chọn cách xử lý khi chạy.

```mermaid
classDiagram
  class ExpenseAggregationStrategy {
    <<interface>>
    +aggregate(List~ImportedExpense~) List~ExpenseSummary~
  }
  class ByCategoryStrategy
  class ByMonthStrategy
  class ExpenseStatisticsService {
    +summarize(ExpenseAggregationStrategy, List~ImportedExpense~)
  }
  ExpenseAggregationStrategy <|.. ByCategoryStrategy
  ExpenseAggregationStrategy <|.. ByMonthStrategy
  ExpenseStatisticsService --> ExpenseAggregationStrategy
```

**Luồng demo:** `GET /api/v1/expense-stats?from=2026-01-01&to=2026-12-31&groupBy=category` (hoặc `month`) → `ExpenseStatsController.view()` → `ExpenseStatisticsService.stats()` chỉ đọc dữ liệu nhập của người gọi → chọn `ByCategoryStrategy`/`ByMonthStrategy` → `ExpenseStatisticsService.summarize()` gọi `aggregate()` → tổng VND và số khoản chi. **Vì sao cần:** hai thuật toán nhóm trên cùng `ImportedExpense` có thể thay thế qua giao diện. **Hỏi đáp:** “Đổi Strategy lúc nào?” — theo `groupBy` của request; kết quả không phụ thuộc định dạng CSV nguồn.
