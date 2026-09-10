package com.example.fileserver;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 全局跨域配置。
 *
 * <p>这是一个示例服务，为了方便页面/脚本从任意来源直接调接口，这里对所有路径放开
 * 任意来源、任意方法和任意请求头。生产环境请改成白名单，只放行可信域名。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		registry.addMapping("/**")
				// allowedOriginPatterns 而不是 allowedOrigins：允许 "*" 与凭据共存
				.allowedOriginPatterns("*")
				.allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD")
				.allowedHeaders("*");
	}
}
