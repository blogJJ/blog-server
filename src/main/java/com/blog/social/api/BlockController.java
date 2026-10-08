package com.blog.social.api;

import com.blog.common.security.AuthUser;
import com.blog.social.service.BlockService;
import com.blog.social.service.BlockService.BlockedRow;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 차단 (T104, SOC-05). 차단 목록은 내 정보 화면에서 풀 때 쓴다. */
@RestController
public class BlockController {

  private final BlockService blockService;

  public BlockController(BlockService blockService) {
    this.blockService = blockService;
  }

  @PostMapping("/api/users/{id}/block")
  public ResponseEntity<Void> block(@AuthenticationPrincipal AuthUser user, @PathVariable Long id) {
    blockService.block(user.id(), id);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/api/users/{id}/block")
  public ResponseEntity<Void> unblock(
      @AuthenticationPrincipal AuthUser user, @PathVariable Long id) {
    blockService.unblock(user.id(), id);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/api/me/blocks")
  public List<BlockedRow> myBlocks(@AuthenticationPrincipal AuthUser user) {
    return blockService.myBlocks(user.id());
  }
}
