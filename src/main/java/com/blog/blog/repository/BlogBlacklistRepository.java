package com.blog.blog.repository;

import com.blog.blog.domain.BlogBlacklist;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BlogBlacklistRepository extends JpaRepository<BlogBlacklist, Long> {

  /** 참여 신청 때 확인. 이메일이나 전화번호 해시가 같은, 해제되지 않은 기록 (BLG-11) */
  @Query(
      "select b from BlogBlacklist b where b.blog.id = :blogId and b.releasedAt is null"
          + " and (b.emailHash = :emailHash or b.phoneHash = :phoneHash) order by b.id desc")
  List<BlogBlacklist> findActiveMatches(
      @Param("blogId") Long blogId,
      @Param("emailHash") String emailHash,
      @Param("phoneHash") String phoneHash);

  /** 관리 화면 목록, 최신순 */
  @Query(
      "select b from BlogBlacklist b join fetch b.registeredBy left join fetch b.user"
          + " where b.blog.id = :blogId order by b.id desc")
  List<BlogBlacklist> findForBlog(@Param("blogId") Long blogId);

  Optional<BlogBlacklist> findByIdAndBlogId(Long id, Long blogId);
}
