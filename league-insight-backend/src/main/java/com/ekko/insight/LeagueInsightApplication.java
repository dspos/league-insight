package com.ekko.insight;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 英雄联盟战绩查询工具后端
 *
 * @author ekko
 */
@SpringBootApplication
@EnableAsync
public class LeagueInsightApplication {

    public static void main(String[] args) {
        SpringApplication.run(LeagueInsightApplication.class, args);
    }
}