package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.IFavoriteService;
import java.util.List;
import java.util.Map;
import lombok.Data;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/favorites")
public class FavoriteController {
  private final IFavoriteService favorites;

  // Keep intentional client errors out of the legacy catch-all runtime advice.
  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<Result<Void>> handleStatus(ResponseStatusException error) {
    return ResponseEntity.status(error.getStatus()).body(Result.fail(error.getReason()));
  }

  public FavoriteController(IFavoriteService favorites) {
    this.favorites = favorites;
  }

  @GetMapping
  public Result<List<Map<String, Object>>> list(
      @RequestParam(defaultValue = "shop") String kind,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "12") int size) {
    return favorites.list(kind, page, size);
  }

  @GetMapping("/{kind}/{id}")
  public Result<Boolean> state(@PathVariable String kind, @PathVariable Long id) {
    return favorites.state(kind, id);
  }

  @PutMapping("/{kind}/{id}")
  public Result<Boolean> set(
      @PathVariable String kind, @PathVariable Long id, @RequestBody SetRequest request) {
    return favorites.set(kind, id, request.getSaved());
  }

  @Data
  public static class SetRequest {
    private Boolean saved;
  }
}
