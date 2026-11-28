package com.hmdp.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface FavoriteMapper {
  long countFavorite(
      @Param("userId") long userId, @Param("kind") String kind, @Param("targetId") long targetId);

  Long lockOwner(@Param("userId") long userId);

  long countTarget(@Param("kind") String kind, @Param("targetId") long targetId);

  int save(
      @Param("userId") long userId, @Param("kind") String kind, @Param("targetId") long targetId);

  int remove(
      @Param("userId") long userId, @Param("kind") String kind, @Param("targetId") long targetId);

  long countByOwner(@Param("userId") long userId, @Param("kind") String kind);

  List<Map<String, Object>> listByOwner(
      @Param("userId") long userId,
      @Param("kind") String kind,
      @Param("limit") int limit,
      @Param("offset") int offset);
}
