package org.dacnpm.schedulemanager.schedule.catalog;

import java.util.List;
import java.util.Map;
import org.dacnpm.schedulemanager.schedule.catalog.CatalogRepository.Item;
import org.dacnpm.schedulemanager.schedule.catalog.CatalogRepository.UserLinks;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Danh mục tối thiểu cho ca: /api/schedule/catalog/{locations|positions|skills|certificates}.
 * Chỉ role admin truy cập (/api/schedule/** trong SecurityConfig).
 */
@Profile("sqlserver")
@RestController
@RequestMapping("/api/schedule/catalog")
public class CatalogController {

    public record NameRequest(String name) {
    }

    private final CatalogRepository repository;

    public CatalogController(CatalogRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/{type}")
    public List<Item> list(@PathVariable String type) {
        return repository.list(type);
    }

    @PostMapping("/{type}")
    public ResponseEntity<Item> create(@PathVariable String type, @RequestBody NameRequest request) {
        if (!CatalogRepository.isKnown(type)) {
            throw new IllegalArgumentException("Danh mục không hợp lệ: " + type + ".");
        }
        if (request.name() == null || request.name().isBlank() || request.name().trim().length() > 120) {
            throw new IllegalArgumentException("Tên không được để trống và tối đa 120 ký tự.");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(repository.create(type, request.name().trim()));
    }

    @DeleteMapping("/{type}/{id}")
    public ResponseEntity<Void> delete(@PathVariable String type, @PathVariable String id) {
        return repository.delete(type, id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @GetMapping("/users/{userId}")
    public UserLinks userLinks(@PathVariable String userId) {
        requireUser(userId);
        return repository.userLinks(userId);
    }

    @PutMapping("/users/{userId}")
    public UserLinks replaceUserLinks(@PathVariable String userId, @RequestBody UserLinks links) {
        requireUser(userId);
        repository.replaceUserLinks(userId, links);
        return repository.userLinks(userId);
    }

    private void requireUser(String userId) {
        if (!repository.userExists(userId)) {
            throw new IllegalArgumentException("Không tìm thấy nhân viên " + userId + ".");
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> conflict(DataIntegrityViolationException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "Thao tác không thực hiện được: tên đã tồn tại, mã không hợp lệ hoặc mục đang được sử dụng."));
    }
}
