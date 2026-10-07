package com.example.codejudge.service;

import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LeaderboardService {

    private static final String LEADERBOARD_KEY = "judge:leaderboard:global";
    private final StringRedisTemplate redisTemplate;

    @Getter
    @Builder
    public static class LeaderboardEntry {
        private final int rank;
        private final String userId;
        private final double score;
    }

    public void incrementScore(String userId, double deltaScore) {
        redisTemplate.opsForZSet().incrementScore(LEADERBOARD_KEY, userId, deltaScore);
    }

    public Long getUserRank(String userId) {
        Long rank = redisTemplate.opsForZSet().reverseRank(LEADERBOARD_KEY, userId);
        return rank != null ? rank + 1 : null;
    }

    public List<LeaderboardEntry> getTopUsers(int page, int size) {
        long start = (long) page * size;
        long end = start + size - 1;

        Set<ZSetOperations.TypedTuple<String>> entries =
                redisTemplate.opsForZSet().reverseRangeWithScores(LEADERBOARD_KEY, start, end);

        if (entries == null || entries.isEmpty()) {
            return Collections.emptyList();
        }

        AtomicInteger rankCounter = new AtomicInteger((int) start + 1);
        return entries.stream()
                .map(tuple -> LeaderboardEntry.builder()
                        .rank(rankCounter.getAndIncrement())
                        .userId(tuple.getValue())
                        .score(tuple.getScore() != null ? tuple.getScore() : 0.0)
                        .build())
                .collect(Collectors.toList());
    }
}
