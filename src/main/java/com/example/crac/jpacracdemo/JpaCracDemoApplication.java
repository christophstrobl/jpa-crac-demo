package com.example.crac.jpacracdemo;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketImpl;
import java.net.SocketImplFactory;
import java.sql.Connection;
import java.sql.ConnectionBuilder;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariConfigMXBean;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import com.zaxxer.hikari.metrics.MetricsTrackerFactory;
import com.zaxxer.hikari.pool.HikariPool;
import org.slf4j.LoggerFactory;
import org.springframework.beans.DirectFieldAccessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.util.ReflectionUtils;

@SpringBootApplication
public class JpaCracDemoApplication {

	static volatile List<SocketImpl> allSockets = Collections.synchronizedList(new ArrayList<SocketImpl>());

	public static void main(String[] args) throws IOException {

		SpySocketImplFactory spySocketImplFactory = new SpySocketImplFactory(allSockets);
		Socket.setSocketImplFactory(spySocketImplFactory);
		ServerSocket.setSocketFactory(spySocketImplFactory);

		SpringApplication.run(JpaCracDemoApplication.class, args);
	}

	@Bean
	@ConfigurationProperties(
			prefix = "spring.datasource.hikari"
	)
	MyDataSource dataSource(DataSourceProperties properties) {
		return createDataSource(properties, HikariDataSource.class, properties.getClassLoader());
	}


	private static MyDataSource createDataSource(DataSourceProperties properties, Class<? extends DataSource> type, ClassLoader classLoader) {
		return new MyDataSource((HikariDataSource) properties.initializeDataSourceBuilder().type(type).build());
	}

	private static SocketImpl newSocketImpl() {
		try {
			Class<?> defaultSocketImpl = Class.forName("java.net.SocksSocketImpl");
			Constructor<?> constructor = defaultSocketImpl.getDeclaredConstructor(SocketImpl.class);
			constructor.setAccessible(true);
			Method m = ReflectionUtils.findMethod(SocketImpl.class, "createPlatformSocketImpl", boolean.class);
			m.setAccessible(true);
			Object o = ReflectionUtils.invokeMethod(m, null, false);
			return (SocketImpl) constructor.newInstance(o);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private static class SpySocketImplFactory implements SocketImplFactory {

		private final List<SocketImpl> spy;

		public SpySocketImplFactory(List<SocketImpl> spy) {
			this.spy = spy;
		}

		@Override
		public SocketImpl createSocketImpl() {
			SocketImpl socket = newSocketImpl();
			spy.add(socket);
			return socket;
		}
	}

	static class MyDataSource extends HikariDataSource implements DataSource, SmartLifecycle {

		org.slf4j.Logger logger = LoggerFactory.getLogger(MyDataSource.class);

		HikariDataSource delegate;

		@Override
		public void start() {

			if (delegate.getHikariPoolMXBean() instanceof HikariPool pool) {
				if (pool.poolState == HikariPool.POOL_NORMAL) {
					logger.info("hikariCP pool %s already started - nothing to to".formatted(delegate.getPoolName()));
				} else if (pool.poolState == HikariPool.POOL_SHUTDOWN) {
					logger.info("hikariCP pool %s closed - restarting".formatted(delegate.getPoolName()));
					delegate = new HikariDataSource(delegate);
				} else if (pool.poolState == HikariPool.POOL_SUSPENDED) {
					logger.info("hikariCP pool %s suspended - resuming".formatted(delegate.getPoolName()));
					pool.resumePool();
				}
			} else {
				logger.info("hikariCP pool %s not found - initializing new".formatted(delegate.getPoolName()));
				delegate = new HikariDataSource(delegate);
			}
		}

		@Override
		public void stop() {

			System.out.println("Sockets open before closing pool");
			for (SocketImpl impl : allSockets) {
				System.out.println("%s: %s".formatted(impl, getState(impl)));
			}

			if (delegate.getHikariPoolMXBean() instanceof HikariPool pool) {
				if (delegate.isAllowPoolSuspension()) {

					logger.info("suspending HikariPool %s".formatted(delegate.getPoolName()));
					pool.suspendPool();
					logger.info("evicting HikariPool %s connections".formatted(delegate.getPoolName()));
					pool.softEvictConnections();

					CompletableFuture<Void> awaitClosure = CompletableFuture.runAsync(() -> waitForConnectionClosure(pool));
					try {
						awaitClosure.get(delegate.getIdleTimeout() + 1000, TimeUnit.MILLISECONDS);
					} catch (InterruptedException | ExecutionException | TimeoutException e) {
						throw new RuntimeException(e);
					}

					if (pool.getTotalConnections() > 0) {
						logger.info("suspending HikariPool %s failed - closing HikariDataSource".formatted(delegate.getPoolName()));
						delegate.close();
					}

					System.out.println("Sockets after closing pool");
					for (SocketImpl impl : allSockets) {
						System.out.println("%s: %s".formatted(impl, getState(impl)));
					}
					return;
				}
				if (pool.poolState == HikariPool.POOL_NORMAL) {
					logger.info("%s PoolSuspension not allowed - closing HikariDataSource".formatted(delegate.getPoolName()));
					delegate.close();
				}
			} else {
				logger.info("%s is not a HikariPool - closing HikariDataSource".formatted(delegate.getPoolName()));
				delegate.close();
			}
		}

		private static Object getState(SocketImpl impl) {
			try {

				Object delegate1 = new DirectFieldAccessor(impl).getPropertyValue("delegate");
				Object state = new DirectFieldAccessor(delegate1).getPropertyValue("state");
				if (state instanceof Integer i) {
					return switch (i) {
						case 0 -> "new";
						case 1 -> "unconnected";
						case 2 -> "connecting";
						case 3 -> "connected";
						case 4 -> "closing";
						case 5 -> "closed";
						default -> throw new IllegalStateException("Unexpected value: " + i);
					};
				}
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
			return null;
		}

		private void waitForConnectionClosure(HikariPool pool) {

			logger.info("""
					HikariPool %s connections:
						total: %s
						active: %s
						idle: %s
					""".formatted(delegate.getPoolName(), pool.getTotalConnections(), pool.getActiveConnections(), pool.getIdleConnections()));
			while (pool.getTotalConnections() > 0) {
				try {
					TimeUnit.MILLISECONDS.sleep(500);
				} catch (InterruptedException e) {
					logger.error("Interrupted while waiting for connections to be closed", e);
					Thread.currentThread().interrupt();
				}
			}
		}

		@Override
		public boolean isRunning() {

			if (delegate.getHikariPoolMXBean() instanceof HikariPool pool) {
				logger.info("HikariPool %s: %s".formatted(delegate.getPoolName(), poolState(pool.poolState)));
				return pool.poolState == HikariPool.POOL_NORMAL;
			}
			return true;
		}

		String poolState(int value) {
			return switch (value) {
				case HikariPool.POOL_NORMAL -> "running";
				case HikariPool.POOL_SHUTDOWN -> "closed";
				case HikariPool.POOL_SUSPENDED -> "suspended";
				default -> throw new IllegalStateException("Unexpected value: " + value);
			};
		}

		public MyDataSource(HikariDataSource delegate) {
			this.delegate = delegate;
		}

		public void setMetricRegistry(Object metricRegistry) {
			delegate.setMetricRegistry(metricRegistry);
		}

		public void setMetricsTrackerFactory(MetricsTrackerFactory metricsTrackerFactory) {
			delegate.setMetricsTrackerFactory(metricsTrackerFactory);
		}

		public void setHealthCheckRegistry(Object healthCheckRegistry) {
			delegate.setHealthCheckRegistry(healthCheckRegistry);
		}

		public HikariPoolMXBean getHikariPoolMXBean() {
			return delegate.getHikariPoolMXBean();
		}

		public HikariConfigMXBean getHikariConfigMXBean() {
			return delegate.getHikariConfigMXBean();
		}

		public void evictConnection(Connection connection) {
			delegate.evictConnection(connection);
		}

		public void close() {
			delegate.close();
		}

		public boolean isClosed() {
			return delegate.isClosed();
		}

		public String getCatalog() {
			return delegate.getCatalog();
		}

		public void setCatalog(String catalog) {
			delegate.setCatalog(catalog);
		}

		public long getConnectionTimeout() {
			return delegate.getConnectionTimeout();
		}

		public void setConnectionTimeout(long connectionTimeoutMs) {
			delegate.setConnectionTimeout(connectionTimeoutMs);
		}

		public long getIdleTimeout() {
			return delegate.getIdleTimeout();
		}

		public void setIdleTimeout(long idleTimeoutMs) {
			delegate.setIdleTimeout(idleTimeoutMs);
		}

		public long getLeakDetectionThreshold() {
			return delegate.getLeakDetectionThreshold();
		}

		public void setLeakDetectionThreshold(long leakDetectionThresholdMs) {
			delegate.setLeakDetectionThreshold(leakDetectionThresholdMs);
		}

		public long getMaxLifetime() {
			return delegate.getMaxLifetime();
		}

		public void setMaxLifetime(long maxLifetimeMs) {
			delegate.setMaxLifetime(maxLifetimeMs);
		}

		public int getMaximumPoolSize() {
			return delegate.getMaximumPoolSize();
		}

		public void setMaximumPoolSize(int maxPoolSize) {
			delegate.setMaximumPoolSize(maxPoolSize);
		}

		public int getMinimumIdle() {
			return delegate.getMinimumIdle();
		}

		public void setMinimumIdle(int minIdle) {
			delegate.setMinimumIdle(minIdle);
		}

		public String getPassword() {
			return delegate.getPassword();
		}

		public void setPassword(String password) {
			delegate.setPassword(password);
		}

		public String getUsername() {
			return delegate.getUsername();
		}

		public void setUsername(String username) {
			delegate.setUsername(username);
		}

		public long getValidationTimeout() {
			return delegate.getValidationTimeout();
		}

		public void setValidationTimeout(long validationTimeoutMs) {
			delegate.setValidationTimeout(validationTimeoutMs);
		}

		public String getConnectionTestQuery() {
			return delegate.getConnectionTestQuery();
		}

		public void setConnectionTestQuery(String connectionTestQuery) {
			delegate.setConnectionTestQuery(connectionTestQuery);
		}

		public String getConnectionInitSql() {
			return delegate.getConnectionInitSql();
		}

		public void setConnectionInitSql(String connectionInitSql) {
			delegate.setConnectionInitSql(connectionInitSql);
		}

		public DataSource getDataSource() {
			return delegate.getDataSource();
		}

		public void setDataSource(DataSource dataSource) {
			delegate.setDataSource(dataSource);
		}

		public String getDataSourceClassName() {
			return delegate.getDataSourceClassName();
		}

		public void setDataSourceClassName(String className) {
			delegate.setDataSourceClassName(className);
		}

		public void addDataSourceProperty(String propertyName, Object value) {
			delegate.addDataSourceProperty(propertyName, value);
		}

		public String getDataSourceJNDI() {
			return delegate.getDataSourceJNDI();
		}

		public void setDataSourceJNDI(String jndiDataSource) {
			delegate.setDataSourceJNDI(jndiDataSource);
		}

		public Properties getDataSourceProperties() {
			return delegate.getDataSourceProperties();
		}

		public void setDataSourceProperties(Properties dsProperties) {
			delegate.setDataSourceProperties(dsProperties);
		}

		public String getDriverClassName() {
			return delegate.getDriverClassName();
		}

		public void setDriverClassName(String driverClassName) {
			delegate.setDriverClassName(driverClassName);
		}

		public String getJdbcUrl() {
			return delegate.getJdbcUrl();
		}

		public void setJdbcUrl(String jdbcUrl) {
			delegate.setJdbcUrl(jdbcUrl);
		}

		public boolean isAutoCommit() {
			return delegate.isAutoCommit();
		}

		public void setAutoCommit(boolean isAutoCommit) {
			delegate.setAutoCommit(isAutoCommit);
		}

		public boolean isAllowPoolSuspension() {
			return delegate.isAllowPoolSuspension();
		}

		public void setAllowPoolSuspension(boolean isAllowPoolSuspension) {
			delegate.setAllowPoolSuspension(isAllowPoolSuspension);
		}

		public long getInitializationFailTimeout() {
			return delegate.getInitializationFailTimeout();
		}

		public void setInitializationFailTimeout(long initializationFailTimeout) {
			delegate.setInitializationFailTimeout(initializationFailTimeout);
		}

		public boolean isIsolateInternalQueries() {
			return delegate.isIsolateInternalQueries();
		}

		public void setIsolateInternalQueries(boolean isolate) {
			delegate.setIsolateInternalQueries(isolate);
		}

		public MetricsTrackerFactory getMetricsTrackerFactory() {
			return delegate.getMetricsTrackerFactory();
		}

		public Object getMetricRegistry() {
			return delegate.getMetricRegistry();
		}

		public Object getHealthCheckRegistry() {
			return delegate.getHealthCheckRegistry();
		}

		public Properties getHealthCheckProperties() {
			return delegate.getHealthCheckProperties();
		}

		public void setHealthCheckProperties(Properties healthCheckProperties) {
			delegate.setHealthCheckProperties(healthCheckProperties);
		}

		public void addHealthCheckProperty(String key, String value) {
			delegate.addHealthCheckProperty(key, value);
		}

		public long getKeepaliveTime() {
			return delegate.getKeepaliveTime();
		}

		public void setKeepaliveTime(long keepaliveTimeMs) {
			delegate.setKeepaliveTime(keepaliveTimeMs);
		}

		public boolean isReadOnly() {
			return delegate.isReadOnly();
		}

		public void setReadOnly(boolean readOnly) {
			delegate.setReadOnly(readOnly);
		}

		public boolean isRegisterMbeans() {
			return delegate.isRegisterMbeans();
		}

		public void setRegisterMbeans(boolean register) {
			delegate.setRegisterMbeans(register);
		}

		public String getPoolName() {
			return delegate.getPoolName();
		}

		public void setPoolName(String poolName) {
			delegate.setPoolName(poolName);
		}

		public ScheduledExecutorService getScheduledExecutor() {
			return delegate.getScheduledExecutor();
		}

		public void setScheduledExecutor(ScheduledExecutorService executor) {
			delegate.setScheduledExecutor(executor);
		}

		public String getTransactionIsolation() {
			return delegate.getTransactionIsolation();
		}

		public String getSchema() {
			return delegate.getSchema();
		}

		public void setSchema(String schema) {
			delegate.setSchema(schema);
		}

		public String getExceptionOverrideClassName() {
			return delegate.getExceptionOverrideClassName();
		}

		public void setExceptionOverrideClassName(String exceptionOverrideClassName) {
			delegate.setExceptionOverrideClassName(exceptionOverrideClassName);
		}

		public void setTransactionIsolation(String isolationLevel) {
			delegate.setTransactionIsolation(isolationLevel);
		}

		public ThreadFactory getThreadFactory() {
			return delegate.getThreadFactory();
		}

		public void setThreadFactory(ThreadFactory threadFactory) {
			delegate.setThreadFactory(threadFactory);
		}

		public void copyStateTo(HikariConfig other) {
			delegate.copyStateTo(other);
		}

		public void validate() {
			delegate.validate();
		}

		@Override
		public Connection getConnection() throws SQLException {
			return delegate.getConnection();
		}

		@Override
		public Connection getConnection(String username, String password) throws SQLException {
			return delegate.getConnection(username, password);
		}

		@Override
		public PrintWriter getLogWriter() throws SQLException {
			return delegate.getLogWriter();
		}

		@Override
		public void setLogWriter(PrintWriter out) throws SQLException {
			delegate.setLogWriter(out);
		}

		@Override
		public void setLoginTimeout(int seconds) throws SQLException {
			delegate.setLoginTimeout(seconds);
		}

		@Override
		public int getLoginTimeout() throws SQLException {
			return delegate.getLoginTimeout();
		}

		@Override
		public Logger getParentLogger() throws SQLFeatureNotSupportedException {
			return delegate.getParentLogger();
		}

		@Override
		public ConnectionBuilder createConnectionBuilder() throws SQLException {
			return delegate.createConnectionBuilder();
		}

		@Override
		public boolean isAutoStartup() {
			return true;
		}

		@Override
		public <T> T unwrap(Class<T> iface) throws SQLException {
			return delegate.unwrap(iface);
		}

		@Override
		public boolean isWrapperFor(Class<?> iface) throws SQLException {
			return delegate.isWrapperFor(iface);
		}
	}
}
