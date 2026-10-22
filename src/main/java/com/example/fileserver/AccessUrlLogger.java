package com.example.fileserver;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 启动完成后在控制台打印可访问地址，方便直接把链接发给同一局域网的人。
 *
 * <p>输出形如：
 * <pre>
 * ----------------------------------------------------------------
 *   应用已启动，可通过以下地址访问：
 *     本机访问   http://localhost:8080/
 *     本机访问   http://127.0.0.1:8080/
 *     局域网访问 http://192.168.0.18:8080/
 *     H2 控制台  http://localhost:8080/h2-console
 * ----------------------------------------------------------------
 * </pre>
 *
 * <p>几个边界情况的处理：
 * <ul>
 *   <li>端口取 {@code local.server.port}（Web 服务器实际监听的端口），所以 {@code server.port=0}
 *       这种随机端口也能打印正确；取不到才退回配置项 {@code server.port}。</li>
 *   <li>配置了 {@code server.address} 时只绑定某张网卡，此时只打印那一个地址。</li>
 *   <li>配置了 {@code server.servlet.context-path} 时，地址会带上该前缀。</li>
 *   <li>局域网地址只取 site-local IPv4（10.x / 172.16-31.x / 192.168.x），
 *       并优先使用物理网卡；VPN、容器网桥等虚拟网卡只作为兜底，
 *       避免打印出别人根本连不上的地址。</li>
 * </ul>
 */
@Component
public class AccessUrlLogger {

	private static final Logger log = LoggerFactory.getLogger(AccessUrlLogger.class);

	/** 输出块的分隔线。 */
	private static final String LINE = "-".repeat(64);

	private final Environment environment;

	public AccessUrlLogger(Environment environment) {
		this.environment = environment;
	}

	/** 应用就绪后打印地址；此时 Web 服务器已经启动，端口是最终值。 */
	@EventListener(ApplicationReadyEvent.class)
	public void printAccessUrls() {
		int port = resolvePort();
		String basePath = resolveBasePath();
		String scheme = environment.getProperty("server.ssl.enabled", Boolean.class, false) ? "https://" : "http://";
		// server.address 一旦配置，服务只监听该网卡，其他地址都不通，因此不再枚举网卡
		String boundAddress = environment.getProperty("server.address", "").trim();

		List<String> localAddresses = new ArrayList<>();
		List<String> networkAddresses = new ArrayList<>();
		boolean wildcard = "0.0.0.0".equals(boundAddress) || "::".equals(boundAddress);
		if (StringUtils.hasText(boundAddress) && !wildcard) {
			localAddresses.add(boundAddress);
		} else {
			localAddresses.add("localhost");
			localAddresses.add("127.0.0.1");
			networkAddresses.addAll(localIpv4Addresses());
		}

		String newline = System.lineSeparator();
		StringBuilder out = new StringBuilder(512);
		out.append(newline).append(LINE).append(newline);
		out.append("  应用已启动，可通过以下地址访问：").append(newline);
		for (String host : localAddresses) {
			out.append("    本机访问   ").append(scheme).append(host).append(':').append(port).append(basePath).append(newline);
		}
		for (String host : networkAddresses) {
			out.append("    局域网访问 ").append(scheme).append(host).append(':').append(port).append(basePath).append(newline);
		}
		// 一条地址都没枚举到时给个明确提示，免得让人以为是漏打印了
		if (StringUtils.hasText(boundAddress) && !wildcard && networkAddresses.isEmpty()) {
			out.append("    (已通过 server.address 绑定到指定网卡)").append(newline);
		} else if (networkAddresses.isEmpty()) {
			out.append("    (未检测到局域网 IPv4 地址)").append(newline);
		}
		// H2 控制台只在开启时才提示，避免打印一个 404 的地址
		if (isH2ConsoleEnabled()) {
			out.append("    H2 控制台  ").append(scheme).append("localhost:").append(port)
					.append(basePath.substring(0, basePath.length() - 1))
					.append(normalizePath(environment.getProperty("spring.h2.console.path", "/h2-console")))
					.append(newline);
		}
		out.append(LINE);
		log.info(out.toString());
	}

	/**
	 * 取实际监听端口。
	 *
	 * <p>{@code local.server.port} 由 Spring Boot 在 Web 服务器启动后写入 Environment，
	 * 是唯一能反映「随机端口」的值；取不到时退回配置的 {@code server.port}，默认 8080。
	 */
	private int resolvePort() {
		String port = environment.getProperty("local.server.port");
		if (!StringUtils.hasText(port)) {
			port = environment.getProperty("server.port", "8080");
		}
		try {
			return Integer.parseInt(port.trim());
		} catch (NumberFormatException e) {
			return 8080;
		}
	}

	/** 返回以 {@code /} 开头和结尾的上下文路径，根路径统一表示成 {@code /}。 */
	private String resolveBasePath() {
		String contextPath = environment.getProperty("server.servlet.context-path", "");
		if (!StringUtils.hasText(contextPath) || "/".equals(contextPath.trim())) {
			return "/";
		}
		String path = contextPath.trim();
		if (!path.startsWith("/")) {
			path = "/" + path;
		}
		if (path.endsWith("/")) {
			path = path.substring(0, path.length() - 1);
		}
		return path + "/";
	}

	/** H2 控制台开关（Spring Boot 4 里由独立的 spring-boot-h2console 模块生效）。 */
	private boolean isH2ConsoleEnabled() {
		return environment.getProperty("spring.h2.console.enabled", Boolean.class, false);
	}

	/** 把控制台路径补成以 {@code /} 开头，便于直接拼到地址后面。 */
	private static String normalizePath(String path) {
		if (!StringUtils.hasText(path)) {
			return "/h2-console";
		}
		String value = path.trim();
		return value.startsWith("/") ? value : "/" + value;
	}

	/**
	 * 收集本机的 site-local IPv4 地址。
	 *
	 * <p>物理网卡的结果放在 {@code preferred}，虚拟网卡（VPN 隧道、容器网桥等）放在 {@code fallback}；
	 * 只要物理网卡有地址就只用物理网卡的，全都没有才退而求其次用虚拟网卡的，
	 * 这样常见场景下不会打印出一堆连不通的地址。
	 */
	private static List<String> localIpv4Addresses() {
		// LinkedHashSet 顺带去重并保留网卡枚举顺序
		Set<String> preferred = new LinkedHashSet<>();
		Set<String> fallback = new LinkedHashSet<>();

		for (NetworkInterface networkInterface : networkInterfaces()) {
			// 跳过回环和未启用的网卡
			if (isLoopback(networkInterface) || !isUp(networkInterface)) {
				continue;
			}
			Set<String> target = isVirtual(networkInterface) ? fallback : preferred;
			for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
				if (isUsableIpv4(address)) {
					target.add(address.getHostAddress());
				}
			}
		}
		return new ArrayList<>(preferred.isEmpty() ? fallback : preferred);
	}

	/** 是否是「可对外访问」的 IPv4：排除回环、链路本地（169.254.x）和非私有网段。 */
	private static boolean isUsableIpv4(InetAddress address) {
		return address instanceof Inet4Address
				&& !address.isLoopbackAddress()
				&& !address.isLinkLocalAddress()
				&& address.isSiteLocalAddress();
	}

	/** 枚举网卡；失败（极少见）时返回空列表，不影响启动。 */
	private static List<NetworkInterface> networkInterfaces() {
		try {
			return Collections.list(NetworkInterface.getNetworkInterfaces());
		} catch (SocketException e) {
			log.debug("Could not enumerate network interfaces", e);
			return List.of();
		}
	}

	/** 下面三个包装方法都是为了吞掉 SocketException，取值失败时给出安全的默认值。 */

	private static boolean isUp(NetworkInterface networkInterface) {
		try {
			return networkInterface.isUp();
		} catch (SocketException e) {
			return false;
		}
	}

	private static boolean isLoopback(NetworkInterface networkInterface) {
		try {
			return networkInterface.isLoopback();
		} catch (SocketException e) {
			// 取不到就当作回环跳过，避免误报地址
			return true;
		}
	}

	private static boolean isVirtual(NetworkInterface networkInterface) {
		// isVirtual() 不声明受检异常，直接调用即可
		return networkInterface.isVirtual();
	}
}
