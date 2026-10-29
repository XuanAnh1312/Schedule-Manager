# Tài liệu API backend

Backend Spring Boot chạy ở `http://localhost:8081`, profile `sqlserver`. Mọi endpoint trừ đăng nhập/đăng xuất và health đều cần JWT. Request mẫu có trong `api.http`.

## 1. Quy ước chung

### 1.1. Xác thực và quyền

- Header: `Authorization: Bearer <token>`, lấy token từ `POST /api/auth/login`.
- Thiếu token, token sai hoặc hết hạn: `401 {"error":"Unauthorized"}`.
- Người gọi và role lấy từ JWT. API **không** nhận `userId`/`role` của người gọi từ body hay query.
- Hai role: `admin` và `user` (nhân viên). Quyền theo thao tác được kiểm tra trong service; ẩn nút trên giao diện không thay thế bước này.

### 1.2. Định dạng lỗi

Các API của kỳ đăng ký, thời gian rảnh và quy tắc trả lỗi nghiệp vụ dạng:

```json
{ "error": "Đã đóng đăng ký — hạn chót 05/10/2026 23:59", "code": "REGISTRATION_CLOSED", "details": ["…"] }
```

`error` là thông báo hiển thị được cho người dùng; `code` ổn định để client xử lý; `details` chỉ có khi có danh sách lỗi (ví dụ lỗi từng trường).

| HTTP | Khi nào |
|---|---|
| 400 | Dữ liệu sai, thiếu xác nhận/lý do |
| 403 | Không có quyền, hoặc kỳ không áp dụng cho nhân viên |
| 404 | Không tìm thấy; kỳ nháp cũng trả 404 với nhân viên |
| 409 | Trạng thái không cho phép (đã đóng đăng ký, sai bước chuyển, dữ liệu đã bị người khác thay đổi) |

### 1.3. Thời gian

- Giờ trong request/response là giờ địa phương nghiệp vụ `Asia/Ho_Chi_Minh`, định dạng ISO không có offset: `2026-11-05T23:59`. Kỳ trả kèm trường `timezone`.
- Ngày: `2026-11-05`. Giờ trong ngày: `07:00`.
- DB lưu thời điểm theo UTC. Hạn chót được quyết định bằng giờ server.

### 1.4. Danh sách endpoint

| Method | Endpoint | Quyền | Mục |
|---|---|---|---|
| POST | `/api/auth/login` | Công khai | — |
| POST | `/api/auth/logout` | Công khai (chỉ nhắc client bỏ token, không thu hồi JWT) | — |
| GET | `/api/health`, `/api/health/database` | Công khai | — |
| GET | `/api/levels` | Đã đăng nhập | — |
| GET | `/api/periods` | Admin: mọi kỳ · Nhân viên: kỳ đã công bố áp dụng cho mình | 2.1 |
| GET | `/api/periods/{id}` | Như trên | 2.1 |
| POST | `/api/periods` | Admin | 2.2 |
| PUT | `/api/periods/{id}` | Admin | 2.3 |
| POST | `/api/periods/{id}/open` · `/lock` · `/reopen` · `/archive` | Admin | 2.4 |
| DELETE | `/api/periods/{id}` | Admin, chỉ kỳ nháp | 2.4 |
| GET · PUT | `/api/availability/my?periodId=` | Nhân viên | 3.1 |
| POST | `/api/availability/my/submit?periodId=` | Nhân viên | 3.1 |
| GET | `/api/availability/progress?periodId=` | Admin | 3.2 |
| GET | `/api/availability/summary?periodId=` | Admin | 3.2 |
| GET | `/api/availability/submitted?periodId=` | Admin | 3.2 |
| GET | `/api/rules/catalog` | Đã đăng nhập | 4.1 |
| GET | `/api/rules/effective?userId=&date=` | Admin: mọi nhân viên · Nhân viên: chỉ của mình | 4.2 |
| GET | `/api/rules/readiness?periodId=` | Admin | 4.2 |
| GET | `/api/rules/settings?scopeType=&scopeId=&includeInactive=` | Admin | 4.2 |
| GET | `/api/rules/templates` | Admin | 4.3 |
| POST | `/api/rules/changes` | Admin | 4.3 |
| POST | `/api/rules/changes/{id}/apply` · `/cancel` | Admin | 4.3 |
| GET | `/api/rules/changes?status=&limit=` · `/api/rules/changes/{id}` | Admin | 4.4 |

## 2. Kỳ đăng ký — `/api/periods`

### 2.1. Đối tượng kỳ (`PeriodView`)

| Trường | Ý nghĩa |
|---|---|
| `status` | Giá trị lưu DB: `draft` · `open` · `locked` · `archived` |
| `registrationState` | Tính theo giờ server: `hidden` (nháp) · `upcoming` (chưa tới `openAt`) · `open` · `closed` · `archived` |
| `statusLabel` | Nhãn hiển thị, ví dụ `Đã đóng đăng ký — hạn chót 05/10/2026 23:59`, `Đang mở đăng ký — kỳ đóng thủ công` |
| `registrationOpen` | `true` khi nhân viên đang được lưu/gửi đăng ký |
| `openAt`, `deadline`, `timezone` | Giờ địa phương nghiệp vụ |
| `autoLock`, `manualClose` | `manualClose = !autoLock`: kỳ chỉ đóng bằng thao tác của Admin |
| `allowReopenByAdmin` | Cho phép mở lại sau khi đóng |
| `requiredDaysOfWeek` | Thứ cần đăng ký, `0` = Chủ nhật … `6` = Thứ bảy |
| `exemptDates`, `closedDates` | Ngày miễn đăng ký, ngày đóng |
| `applicableUserIds` | Chỉ trả cho admin; rỗng = mọi nhân viên đang hoạt động |
| `version` | Gửi lại khi sửa/chuyển trạng thái để chống ghi đè |

"Đang xếp lịch" và "Đã công bố" thuộc API phương án lịch, không có trong kỳ (xem mục 5).

### 2.2. Tạo kỳ — `POST /api/periods` → `201`

```json
{
  "name": "Kỳ tháng 11/2026", "cycle": "monthly",
  "startDate": "2026-11-01", "endDate": "2026-11-30",
  "openAt": "2026-10-20T08:00", "deadline": "2026-11-05T23:59",
  "autoLock": true, "allowReopenByAdmin": true,
  "requiredDaysOfWeek": [1, 2, 3, 4, 5, 6],
  "exemptDates": [], "closedDates": [], "applicableUserIds": []
}
```

- Kỳ mới luôn ở `draft`.
- Kiểm tra: `name` 1–120 ký tự; `cycle` là `weekly`/`monthly`/`yearly`; `endDate ≥ startDate`, tối đa 366 ngày; `deadline > openAt`; ít nhất một thứ cần đăng ký; ngày miễn/đóng nằm trong kỳ và không trùng nhau; `applicableUserIds` là nhân viên đang hoạt động.
- `autoLock`, `allowReopenByAdmin` mặc định `true`.

### 2.3. Sửa kỳ — `PUT /api/periods/{id}`

Body như khi tạo, **bắt buộc** có `version`.

| Trạng thái | Được sửa |
|---|---|
| `draft` | Mọi trường |
| `open` | Chỉ `name`, `deadline`, `autoLock`. Đổi trường khác → `409 FIELD_LOCKED_WHILE_OPEN`, `details` liệt kê trường bị đổi. Nếu kỳ tự khóa, hạn chót phải ở tương lai |
| `locked`, `archived` | Không sửa được (`409 PERIOD_NOT_EDITABLE`); phải mở lại trước |

### 2.4. Chuyển trạng thái

Body tùy chọn: `{"version": 3}`. Nếu có, `version` phải khớp.

| Endpoint | Chuyển | Điều kiện |
|---|---|---|
| `POST /{id}/open` | `draft → open` | Có thứ cần đăng ký; kỳ tự khóa phải có hạn chót ở tương lai |
| `POST /{id}/lock` | `open → locked` | Đóng thủ công, `autoLock` bật hay tắt đều được. Có thể gửi `reason` |
| `POST /{id}/reopen` | `locked → open` | Xem dưới |
| `POST /{id}/archive` | `locked → archived` | Không còn phương án lịch `draft`/`pending_approval`/`approved`; nếu còn → `409 PLAN_NOT_FINALIZED`, `details` liệt kê phương án |
| `DELETE /{id}` → `204` | Xóa | Chỉ kỳ `draft` chưa có đăng ký |

Mở lại kỳ (TU-03, AC-06):

```json
{ "confirm": true, "reason": "Bổ sung nhân viên mới", "newDeadline": "2026-11-08T23:59", "version": 3 }
```

- Cần `confirm: true`, `reason` (tối đa 1000 ký tự) và `allowReopenByAdmin = true`.
- Kỳ tự khóa bắt buộc có `newDeadline` ở tương lai và sau `openAt`; nếu không, tác vụ nền sẽ khóa lại ngay.
- Đăng ký đã gửi giữ nguyên. Lịch đã công bố không bị sửa.

Mọi chuyển trạng thái đều ghi `history`, gồm người thực hiện, thời gian, trước/sau và lý do nếu có.

### 2.5. Tác vụ tự khóa

- Mặc định chạy mỗi 60 giây (`app.periods.auto-lock-delay-ms`). Tác vụ chuyển các kỳ `open` có `autoLock` và đã qua hạn sang `locked`, rồi ghi `history` với người thực hiện là `system`.
- Tắt bằng `app.scheduling.enabled=false`, dùng chung cờ với job nền của module thông báo.
- Dù tác vụ chưa chạy, API đăng ký vẫn tự chặn ngay khi qua hạn.

## 3. Thời gian rảnh — `/api/availability`

### 3.1. Nhân viên

| Endpoint | Ý nghĩa |
|---|---|
| `GET /my?periodId=` | Kỳ, bản đăng ký (`status`: `none`/`draft`/`submitted`), `editable` và `statusLabel` tính theo giờ server |
| `PUT /my?periodId=` | Lưu: tạo mới thành `draft`, hoặc sửa bản đang có và giữ nguyên trạng thái |
| `POST /my/submit?periodId=` | Gửi: lưu nội dung trên form với trạng thái `submitted`. Cần ít nhất một ngày |

Body của `PUT` và `POST` là **toàn bộ** các ngày trên form; bản cũ bị thay thế:

```json
{
  "entries": [
    { "date": "2026-11-02", "slots": [ { "start": "07:00", "end": "12:00" },
                                       { "start": "13:00", "end": "17:00", "type": "unavailable" } ] },
    { "date": "2026-11-03", "slots": [ { "start": "00:00", "end": "23:59" } ] }
  ]
}
```

- `type`: `available` (mặc định) hoặc `unavailable`. `00:00`–`23:59` là cả ngày, tính 24 giờ.
- Ngày hợp lệ: nằm trong kỳ, thuộc `requiredDaysOfWeek`, không phải ngày miễn/đóng, không lặp. Ngày có `slots` rỗng sẽ bị bỏ qua.
- Khoảng giờ hợp lệ: `start < end`, không chồng nhau trong cùng ngày, tối đa 12 khoảng mỗi ngày.
- **Hạn chót (AC-04, AC-05):** mỗi lần lưu/gửi, backend khóa dòng kỳ và tính lại cửa sổ đăng ký bằng giờ server trong cùng giao dịch. Ngoài cửa sổ trả `409 REGISTRATION_NOT_OPEN` hoặc `409 REGISTRATION_CLOSED`, kể cả khi form đã mở từ trước hạn. Dữ liệu đã gửi giữ nguyên.
- Admin không đăng ký thay (`403`). Nhân viên không thuộc đối tượng áp dụng hoặc không hoạt động nhận `403`.

### 3.2. Admin

| Endpoint | Trả về |
|---|---|
| `GET /progress?periodId=` | Số nhân viên áp dụng, đã gửi, đang nháp, chưa đăng ký và danh sách từng người |
| `GET /summary?periodId=` | Theo từng ngày: ngày cần đăng ký/miễn/đóng, số người rảnh, số người không thể làm, tổng giờ rảnh. Chỉ tính bản đã gửi |
| `GET /submitted?periodId=` | Chi tiết các bản đã gửi, là nguồn dữ liệu cho phân lịch |

## 4. Quy tắc — `/api/rules`

### 4.1. Danh mục tham số — `GET /catalog`

Mỗi tham số có `key`, `label`, `unit`, `description`, kiểu, khoảng hợp lệ, `kind` (`hard`/`soft`/`display`), nhóm ưu tiên, có bắt buộc trước khi xếp lịch tự động hay không, và có chỉ cấu hình ở phạm vi công ty hay không.

| Tham số | Đơn vị | Bắt buộc | Ghi chú |
|---|---|---|---|
| `target_hours` | giờ/kỳ | Có | Mục tiêu để cân bằng, không phải giới hạn |
| `max_hours_mode` | `fixed`/`percent` | Có | Cách đặt giờ tối đa |
| `max_hours_fixed` | giờ/kỳ | Khi `fixed` | |
| `max_hours_percent` | % mục tiêu | Khi `percent` | Hệ thống tự tính giờ tối đa = mục tiêu × % / 100 |
| `warning_threshold_percent` | % giờ tối đa | Không | Ngưỡng cảnh báo gần giới hạn |
| `max_hours_per_day`, `max_hours_per_week` | giờ | Có | |
| `min_rest_hours` | giờ | Có | Nghỉ tối thiểu giữa hai ca |
| `max_consecutive_days` | ngày | Có | |
| `weight_balance_hours`, `weight_balance_shifts`, `weight_availability_preference`, `weight_cost` | 0–100 | Không | Chỉ phạm vi công ty |
| `report_rounding_decimals` | 0–2 | Không | Chỉ đổi cách hiển thị, không đổi công thức |
| `level_substitutions` | JSON cặp Level | Không | Chỉ phạm vi công ty, có hiệu lực ngay |

Không có giá trị mặc định nghiệp vụ. Hạn đăng ký cấu hình theo từng kỳ (`/api/periods`). Mốc nhắc hạn và cấu hình duyệt/thông báo do module thông báo quản lý; `catalog.relatedSettings` chỉ tới đúng chỗ.

### 4.2. Quy tắc có hiệu lực

`GET /effective?userId=nv001&date=2026-11-01`: bỏ `userId` thì trả quy tắc của người gọi; bỏ `date` thì tính cho hôm nay.

```json
{
  "values": [ { "key": "target_hours", "value": "60", "source": { "scopeType": "company", "effectiveFrom": "2026-10-29" } } ],
  "derived": { "targetHours": 60, "maxHoursBasis": "percent", "maxHours": 75.00,
               "maxHoursFormula": "60 × 125% = 75", "warningHours": 67.50 },
  "missing": [], "issues": [], "readyForScheduling": true,
  "levelSubstitutions": { "pairs": [ … ], "confirmed": true }
}
```

- `missing`: checklist tham số bắt buộc chưa cấu hình.
- `issues`: cấu hình mâu thuẫn, ví dụ `MAX_BELOW_TARGET` (giờ tối đa nhỏ hơn mục tiêu), `DAY_ABOVE_WEEK`. Còn `missing` hoặc `issues` thì `readyForScheduling = false`.
- `levelSubstitutions.confirmed = false` khi bảng `level_substitutions` vẫn là dữ liệu mẫu V5 chưa được Admin xác nhận.
- `GET /readiness?periodId=`: danh sách nhân viên của kỳ chưa đủ cấu hình. Tính tại ngày bắt đầu kỳ, dùng để kiểm tra trước khi chạy phân lịch.
- `GET /settings`: các giá trị đã lưu; `includeInactive=true` để xem cả bản đã thay thế hoặc đã xóa.

**Thứ tự ưu tiên khi trùng phạm vi (đề xuất, cần cả đội xác nhận):**

1. Ràng buộc pháp luật/an toàn → điều kiện hợp đồng/chứng chỉ → quy tắc doanh nghiệp → tiêu chí ưu tiên của thuật toán. Hai nhóm đầu chưa có dữ liệu trong V1.
2. Cùng một tham số: `user > group > employee_type > branch > company`. Hiện chỉ cấu hình được `company` và `user`, vì chưa có danh mục tổ chức.
3. Cùng phạm vi: giá trị có `effectiveFrom` gần nhất mà không sau ngày đang xét sẽ thắng.

### 4.3. Thay đổi: xem trước → xác nhận

1. `GET /templates`: giá trị **đề xuất** từ dữ liệu mẫu V5, chưa có hiệu lực. Không có đề xuất cho `max_consecutive_days`.
2. `POST /changes` → `201`: kiểm tra và xem trước. **Chưa lưu giá trị nào.**

   ```json
   { "reason": "Cấu hình ban đầu",
     "items": [ { "paramKey": "max_hours_percent", "value": "125" },
                { "op": "set", "paramKey": "target_hours", "scopeType": "user", "scopeId": "nv012",
                  "value": "40", "effectiveFrom": "2026-11-01" },
                { "op": "remove", "paramKey": "target_hours", "scopeType": "user", "scopeId": "nv003" } ] }
   ```

   - `op` mặc định là `set`; `scopeType` mặc định là `company`; `effectiveFrom` mặc định là hôm nay, không được ở quá khứ. `remove` và `level_substitutions` chỉ có hiệu lực ngay. Bắt buộc có `reason`; tối đa 50 thay đổi.
   - Trả về `changeId`, `baseRevision`, `affectedUsers`, `userChanges` (trước/sau từng giá trị và giờ tối đa suy ra, tối đa 100 người), `companyChanges`, `notReadyAfter`, `periods` (kỳ bị ảnh hưởng kèm ghi chú) và `warnings`.
3. `POST /changes/{id}/apply` với `{"confirm": true}`: ghi giá trị mới. Bản cũ chuyển `superseded`/`removed` (không xóa), đồng thời ghi `history` với before/after và lý do.
   - Có thay đổi khác được áp dụng sau lần xem trước → `409 STALE_PREVIEW`, phải xem trước lại.
   - Ngày hiệu lực đã qua → `409 PREVIEW_EXPIRED`. Đã áp dụng hoặc đã hủy → `409 CHANGE_NOT_PENDING`.
   - **Không sửa** kỳ, ca, phân công hay lịch đã công bố. Kỳ đã đóng chỉ dùng quy tắc mới khi người có quyền chạy lại phân lịch.
4. `POST /changes/{id}/cancel`: hủy bản xem trước.

### 4.4. Lịch sử

`GET /changes?status=applied|previewed|cancelled|all&limit=50`, mặc định là `applied`, mới nhất trước. Mỗi mục gồm thay đổi, lý do, bản xem trước, người tạo/áp dụng và thời điểm.

## 5. Hợp đồng với phần lịch

Chốt ngày 29/10/2026 (người phụ trách lịch giao cho người phụ trách kỳ quyết định).

| # | Nội dung | Quyết định | Bên thực hiện |
|---|---|---|---|
| 1 | Lưu trữ kỳ khi còn phương án chưa công bố | **Chặn** nếu còn phương án `draft`/`pending_approval`/`approved` (`409 PLAN_NOT_FINALIZED`). Không tự hủy phương án | Kỳ: kiểm tra qua interface `SchedulePlanGate`. Lịch: cài đặt bean trả danh sách phương án chưa hoàn tất |
| 2 | Trạng thái phương án lịch | `draft` (Nháp) → `pending_approval` (Chờ duyệt) → `approved` (Đã duyệt) → `published` (Đã công bố); bản cũ khi công bố phiên bản mới là `superseded`; hủy là `cancelled`. Mức đủ người (`full`/`understaffed`) là chỉ số riêng, **không** phải trạng thái | Lịch |
| 3 | Mở lại kỳ đã có lịch công bố | Cho phép. Không tự chạy lại, không sửa lịch đã công bố. Giao diện cảnh báo khi mở lại; màn hình lịch hiện "Đăng ký đã thay đổi sau khi công bố — cân nhắc chạy lại" khi có đăng ký `updatedAt` sau thời điểm công bố | Kỳ: không sửa lịch. Lịch: nhãn cảnh báo |
| 4 | Phần lịch lấy dữ liệu đăng ký | Gọi service nội bộ `AvailabilityService.submittedForScheduling(periodId)` (chỉ bản đã gửi của nhân viên còn hoạt động, còn thuộc đối tượng áp dụng) và `RuleService.effectiveForScheduling(userId, date)`. API `GET /api/availability/submitted` giữ cho giao diện | Kỳ: cung cấp. Lịch: gọi |

Chỉ chạy phân lịch chính thức khi kỳ `locked`; chạy thử được khi kỳ `open` hoặc `locked`. Kỳ `archived` thì phương án chỉ đọc.

Nhãn ghép trên màn hình: "Đã đóng đăng ký · Lịch: Chờ duyệt", "Đã đóng đăng ký · Lịch: Đã công bố".

## 6. Mã lỗi

| Mã | HTTP | Ý nghĩa |
|---|---|---|
| `UNAUTHORIZED` / `{"error":"Unauthorized"}` | 401 | Chưa đăng nhập, token sai/hết hạn |
| `FORBIDDEN` | 403 | Không có quyền, kỳ không áp dụng, admin đăng ký thay |
| `NOT_FOUND` | 404 | Không tìm thấy (kỳ nháp với nhân viên cũng vậy) |
| `VALIDATION_FAILED` | 400 | Dữ liệu sai; `details` liệt kê từng lỗi |
| `VERSION_REQUIRED` | 400 | Sửa kỳ thiếu `version` |
| `VERSION_CONFLICT` | 409 | Kỳ đã bị người khác thay đổi |
| `PERIOD_NOT_EDITABLE`, `FIELD_LOCKED_WHILE_OPEN` | 409 | Sửa kỳ không đúng trạng thái/trường |
| `INVALID_TRANSITION` | 409 | Chuyển trạng thái sai bước |
| `DEADLINE_REQUIRED`, `DEADLINE_IN_PAST` | 400 | Kỳ tự khóa thiếu hạn hoặc hạn đã qua |
| `CONFIRM_REQUIRED`, `REASON_REQUIRED`, `REASON_TOO_LONG` | 400 | Thiếu xác nhận/lý do |
| `REOPEN_NOT_ALLOWED` | 409 | Kỳ cấu hình không cho mở lại |
| `NEW_DEADLINE_REQUIRED`, `NEW_DEADLINE_IN_PAST`, `NEW_DEADLINE_BEFORE_OPEN` | 400 | Hạn mới khi mở lại không hợp lệ |
| `PERIOD_HAS_REGISTRATIONS` | 409 | Không xóa kỳ đã có đăng ký |
| `PLAN_NOT_FINALIZED` | 409 | Lưu trữ kỳ khi còn phương án lịch chưa hoàn tất |
| `PERIOD_REQUIRED`, `EMPTY_REGISTRATION` | 400 | Thiếu `periodId`; gửi đăng ký không có ngày nào |
| `REGISTRATION_NOT_OPEN`, `REGISTRATION_CLOSED` | 409 | Chưa mở / đã đóng đăng ký |
| `INVALID_SCOPE`, `INVALID_STATUS` | 400 | Tham số lọc không hợp lệ |
| `CHANGE_NOT_PENDING`, `STALE_PREVIEW`, `PREVIEW_EXPIRED` | 409 | Bản xem trước không còn áp dụng được |
| `MAIN_RULES_MISSING` | 409 | Thiếu dòng `schedule_rules` `rules_main` khi ghi Level thay thế |

## 7. Kiểm thử

- `mvn test`: unit test cho cửa sổ đăng ký, bộ giải quy tắc, service kỳ/đăng ký/quy tắc và bảo mật `PeriodController`. Không cần SQL Server.
- `IssueFiveFlowIT`: chạy trên SQL Server thật với database **rỗng** dành riêng, Flyway tới V7. Không chạy trong `mvn test` mặc định:

  ```
  SM_IT_DB_URL="jdbc:sqlserver://localhost:1433;databaseName=ScheduleManager_IT;encrypt=true;trustServerCertificate=true"
  SM_IT_DB_USER=<login> SM_IT_DB_PASSWORD=<mật khẩu>
  mvn test -Dtest=IssueFiveFlowIT -Dsurefire.failIfNoSpecifiedTests=false
  ```

  Bao phủ AC-04, AC-05 (gửi sau hạn khi tác vụ khóa chưa chạy), AC-06 (mở lại cần quyền/xác nhận/lý do, giữ đăng ký), AC-13 và AC-14 (xem trước không ghi, `60 × 125% = 75`, bản xem trước cũ bị từ chối, không đổi kỳ/ca/phân công).

## 8. Giới hạn hiện tại

- SPA chưa gọi các API trên; màn hình vẫn dùng `localStorage`.
- Phạm vi chi nhánh/loại nhân viên/nhóm chờ danh mục tổ chức. Ràng buộc hợp đồng/chứng chỉ chưa có dữ liệu.
- Logout chỉ ở phía client; JWT còn hạn chưa bị thu hồi khi khóa tài khoản.
