package com.fitness.activityservice.controller;

import com.fitness.activityservice.dto.ActivityRequest;
import com.fitness.activityservice.dto.ActivityResponse;
import com.fitness.activityservice.service.ActivityService;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/activities")
@AllArgsConstructor
public class ActivityController {
    private ActivityService activityService;


    @PostMapping
//    public ResponseEntity<ActivityResponse> trackActivity(@RequestBody ActivityRequest request) {
//        return ResponseEntity.ok(activityService.trackActivity(request));
//    }
     public ResponseEntity<ActivityResponse> trackActivity(
            @RequestHeader("X-User-ID") String userId,   // ← add this
            @RequestBody ActivityRequest request) {
        request.setUserId(userId);                        // ← inject userId from header
        return ResponseEntity.ok(activityService.trackActivity(request));
    }
}