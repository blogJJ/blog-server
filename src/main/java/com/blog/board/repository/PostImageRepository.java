package com.blog.board.repository;

import com.blog.board.domain.PostImage;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostImageRepository extends JpaRepository<PostImage, Long> {

  Optional<PostImage> findByStoredName(String storedName);

  List<PostImage> findByStoredNameIn(Collection<String> storedNames);

  List<PostImage> findByPostIdOrderBySortOrderAsc(Long postId);

  /** 목록의 미리보기용. 글마다 첫 이미지(sort_order 0). */
  @Query(
      "select i from PostImage i where i.post.id in :postIds and i.sortOrder = 0"
          + " and i.deletedAt is null")
  List<PostImage> findFirstImages(@Param("postIds") Collection<Long> postIds);

  /** 공유 미리보기용. 글에 붙은 이미지를 본문 순서대로 (T161). */
  @Query(
      "select i from PostImage i where i.post.id = :postId and i.deletedAt is null"
          + " order by i.sortOrder")
  List<PostImage> findAttached(@Param("postId") Long postId);
}
