package com.example.file_concurrency_test;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class FileConcurrencyTestApplicationTests {

	@Test
	void contextLoads() {
	}

	@Test
	void testMain() {
		// Verify main runs without exceptions
		FileConcurrencyTestApplication.main(new String[]{"--server.port=0", "--spring.sql.init.mode=never"});
	}

}
