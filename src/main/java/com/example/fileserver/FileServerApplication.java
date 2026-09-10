package com.example.fileserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 应用入口。
 *
 * <p>{@code @SpringBootApplication} 会开启自动配置、组件扫描（扫描本类所在包及子包）
 * 和配置类注册，因此同包下的 Controller / Service / Entity 都会被自动发现。
 *
 * <p>启动后：Servlet 容器监听 {@code server.port}（默认 8080），
 * {@code upload/} 与 H2 数据文件 {@code data/} 建在进程工作目录下，
 * 控制台会打印可访问地址（见 {@link AccessUrlLogger}）。
 */
@SpringBootApplication
public class FileServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(FileServerApplication.class, args);
	}

}
