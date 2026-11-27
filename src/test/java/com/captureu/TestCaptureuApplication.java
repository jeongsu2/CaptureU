package com.captureu;

import org.springframework.boot.SpringApplication;

public class TestCaptureuApplication {

	public static void main(String[] args) {
		SpringApplication.from(CaptureuApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
