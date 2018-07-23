package com.hjh.test.jedis.lock;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import redis.clients.jedis.Jedis;

public class ReLockFactory implements AutoCloseable {

	// 锁定时间2秒,守护线程等于这个时间除以2
	private int lockTime = 1000;

	// 守护线程线程池
	private ExecutorService executorService = Executors.newCachedThreadPool();

	public int getLockTime() {
		return lockTime;
	}

	public void setLockTime(int lockTime) {
		this.lockTime = lockTime;
	}

	public ExecutorService getExecutorService() {
		return executorService;
	}

	public void setExecutorService(ExecutorService executorService) {
		this.executorService = executorService;
	}

	public ReLock getResource(Jedis jedis, String lockKey, int waitTime) {
		return new ReLock(jedis, lockKey, waitTime, lockTime, executorService);
	}

	@Override
	protected void finalize() throws Throwable {
		executorService.shutdown();
		super.finalize();
	}

	@Override
	public void close() throws Exception {
		executorService.shutdown();
	}
}
