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
 * Prints the addresses the running application can be reached at once startup finishes,
 * for example {@code http://localhost:8080/} and {@code http://192.168.0.18:8080/}.
 */
@Component
public class AccessUrlLogger {

	private static final Logger log = LoggerFactory.getLogger(AccessUrlLogger.class);

	private static final String LINE = "-".repeat(64);

	private final Environment environment;

	public AccessUrlLogger(Environment environment) {
		this.environment = environment;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void printAccessUrls() {
		int port = resolvePort();
		String basePath = resolveBasePath();
		String boundAddress = environment.getProperty("server.address", "").trim();

		List<String> localAddresses = new ArrayList<>();
		List<String> networkAddresses = new ArrayList<>();
		if (StringUtils.hasText(boundAddress)) {
			// server.address pins the listener to one interface, so only that one is reachable.
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
			out.append("    本机访问   http://").append(host).append(':').append(port).append(basePath).append(newline);
		}
		for (String host : networkAddresses) {
			out.append("    局域网访问 http://").append(host).append(':').append(port).append(basePath).append(newline);
		}
		if (StringUtils.hasText(boundAddress) && networkAddresses.isEmpty()) {
			out.append("    (已通过 server.address 绑定到指定网卡)").append(newline);
		} else if (!StringUtils.hasText(boundAddress) && networkAddresses.isEmpty()) {
			out.append("    (未检测到局域网 IPv4 地址)").append(newline);
		}
		if (isH2ConsoleEnabled()) {
			out.append("    H2 控制台  http://localhost:").append(port)
					.append(normalizePath(environment.getProperty("spring.h2.console.path", "/h2-console")))
					.append(newline);
		}
		out.append(LINE);
		log.info(out.toString());
	}

	/** The real listening port, which also covers {@code server.port=0} (random port). */
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

	/** Context path with a leading and trailing slash, e.g. {@code /} or {@code /app/}. */
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

	private boolean isH2ConsoleEnabled() {
		return environment.getProperty("spring.h2.console.enabled", Boolean.class, false);
	}

	private static String normalizePath(String path) {
		if (!StringUtils.hasText(path)) {
			return "/h2-console";
		}
		String value = path.trim();
		return value.startsWith("/") ? value : "/" + value;
	}

	/**
	 * Site-local IPv4 addresses (10.x, 172.16-31.x, 192.168.x) of the usable interfaces.
	 * Virtual interfaces such as VPN tunnels or container bridges are only used as a
	 * fallback, when no physical interface offers an address.
	 */
	private static List<String> localIpv4Addresses() {
		Set<String> preferred = new LinkedHashSet<>();
		Set<String> fallback = new LinkedHashSet<>();

		for (NetworkInterface networkInterface : networkInterfaces()) {
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

	private static boolean isUsableIpv4(InetAddress address) {
		return address instanceof Inet4Address
				&& !address.isLoopbackAddress()
				&& !address.isLinkLocalAddress()
				&& address.isSiteLocalAddress();
	}

	private static List<NetworkInterface> networkInterfaces() {
		try {
			return Collections.list(NetworkInterface.getNetworkInterfaces());
		} catch (SocketException e) {
			log.debug("Could not enumerate network interfaces", e);
			return List.of();
		}
	}

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
			return true;
		}
	}

	private static boolean isVirtual(NetworkInterface networkInterface) {
		return networkInterface.isVirtual();
	}
}
