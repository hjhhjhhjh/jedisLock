package com.hjh.test.jedis.lock;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

import org.apache.logging.log4j.core.util.UuidUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import redis.clients.jedis.Jedis;

/**
 * 
 * @author HJH
 *
 */
public class ReLock implements AutoCloseable {
	private final static Logger logger = LoggerFactory.getLogger(ReLock.class);

	// 记录本地所有被锁的key,左右类似二级缓存
	private static final Set<String> SYSTEM_LOCAL_LOCK_SET = ConcurrentHashMap.<String>newKeySet();

	// 锁定时间20秒
	private int lockTime;

	// 守护线程循环执行时间
	private int halfLockTime;

	// 线程循环等待时间
	private static final int THREAD_WAIT_TIME = 300;

	private ExecutorService executorService;

	// ThreadLocal<HashMap<String, GuardianThread>> tLocal = new
	// ThreadLocal<>();

	private GuardianRun gRun;
	private Jedis jedis;
	private String lockKey;
	private int waitTime;

	/**
	 * 锁状态,默认是解锁
	 */
	private LockStatus lockStatus = LockStatus.UnLock;

	public LockStatus getLockStatus() {
		return lockStatus;
	}

	private String name;

	public String getName() {
		return name;
	}

	public ReLock(Jedis jedis, String lockKey, int waitTime, int lockTime, ExecutorService executorService) {
		this.jedis = jedis;
		this.lockKey = lockKey;
		this.waitTime = waitTime;
		this.lockTime = lockTime;

		this.executorService = executorService;

		halfLockTime = lockTime / 2;

		this.name = UuidUtil.getTimeBasedUuid().randomUUID().toString();
		logger.debug("UUID是{}", this.name);
		MDC.put("requestId", this.name);
	}

	private String requestId = UUID.randomUUID().toString();

	public boolean tryLock() {
		// 设置停止时间
		long stopTime = System.currentTimeMillis() + waitTime;

		// 设置本地缓存锁住key
		boolean isSetSystemLocalSuccess = getSystemLocalLock();
		// 检测本地是否已经有被锁.如果有,就循环等待,直到超过等待时间;如果没有,就调用redis判断有没有被其它服务器锁了.

		boolean isLockTrue = doLock(stopTime, isSetSystemLocalSuccess);
		if (!isLockTrue) {
			return isLockTrue;
		}

		logger.debug("获取到锁");
		logger.debug("jedis.get(lockKey) = {}", jedis.get(lockKey));

		lockStatus = LockStatus.IsLook;
		
		// 创建自延寿命守护线程
		gRun = new GuardianRun(this);
		// 启动守护线程
		executorService.execute(gRun);

		return true;
	}

	private boolean getSystemLocalLock() {
		boolean isTrue = SYSTEM_LOCAL_LOCK_SET.add(lockKey);
		;
		if (isTrue) {
			logger.debug("key={}获取到本地锁", lockKey);
		}
		return isTrue;
	}	
	

	/**
	 * 获取锁
	 * 
	 * @param stopTime
	 *            停止时间
	 * @param isSetSystemLocalSuccess
	 *            是否
	 * @return
	 */
	private boolean doLock(long stopTime, boolean isSetSystemLocalSuccess) {
		// 如果超过等待时间,就返回false
		if (System.currentTimeMillis() > stopTime) {
			// 如果是自己拿到锁就解锁
			if (isSetSystemLocalSuccess) {
				SYSTEM_LOCAL_LOCK_SET.remove(lockKey);
			}
			return false;
		}

		// 判断本地是否有获取到锁
		if (!isSetSystemLocalSuccess) {
			// 检测本地是否已经有被锁
			boolean isTrue = getSystemLocalLock();
			if (!isTrue) {
				logger.trace("获取不到锁睡一会儿再来递归");
				// 睡一会儿再来递归
				threadSleep(THREAD_WAIT_TIME);

				return doLock(stopTime, false);
			}
			logger.debug("获取到本地锁");
		}

		// 获取远程锁
		if (RedisTool.tryGetNXDistributedLock(jedis, lockKey, requestId, lockTime)) {
			logger.debug("远程没有锁,并且拿到锁");
			return true;
		}

		// 睡一会儿再来递归
		threadSleep(THREAD_WAIT_TIME);
		return doLock(stopTime, true);
	}

	/**
	 * 解锁
	 * 
	 * @return 是否解锁成功
	 */
	public boolean unlock() {
		logger.debug("执行解锁!");
		
		// 如果没锁住,直接返回成功
		if (!this.lockStatus.equals(LockStatus.IsLook)) {
			logger.debug("本来就没有锁到!");
			return true;
		}
		

		// TODO:头痛,不知道要先删本地缓存再删远程还是反过来,是否需要判断结果......
		boolean isLocalUnlock = SYSTEM_LOCAL_LOCK_SET.remove(lockKey);

		// 调用Redis解锁
		boolean isUnlock = RedisTool.releaseDistributedLock(jedis, lockKey, requestId);
		if (isUnlock) {
			this.lockStatus = LockStatus.UnLock;
		}

		logger.debug("解锁结果为:" + isUnlock);

		if (gRun != null) {
			// 停止守护
			gRun.setStop();

			/*
			 * while (!gRun.getStopSuccess()) { // 睡一会儿再来循环 try {
			 * logger.debug(System.currentTimeMillis());
			 * Thread.sleep(THREAD_WAIT_TIME); } catch (InterruptedException e)
			 * { e.printStackTrace();// TODO:需要处理吗? } }
			 */
		}

		return isUnlock;
	}

	@Override
	protected void finalize() throws Throwable {
		if (gRun != null) {
			gRun.setStop();
		}
		super.finalize();
	}

	/**
	 * 解锁,关闭
	 */
	@Override
	public void close() throws Exception {
		boolean isUnlock = unlock();
		if (!isUnlock) {
			// 解锁失败了啊,好像没法玩了,按照套路应该不会出现,不然就是这个工具有bug
			logger.debug("jedis.get(lockKey) = {}", jedis.get(lockKey));
			logger.debug("解锁竟然失败了,是不是有其它操作把key给搞掉了?");
			throw new RuntimeException("解锁竟然失败了,是不是有其它操作把key给搞掉了?");
		}
	}

	private void threadSleep(int sleepTime) {
		// 睡一会儿再来递归
		try {
			Thread.sleep(sleepTime);
		} catch (InterruptedException e) {
			logger.debug("sleep被打断了抛出异常", e);
		}
	}

	/**
	 * 守护线程
	 * 
	 * @author HJH
	 *
	 */
	class GuardianRun implements Runnable {
		ReLock reLock;

		public GuardianRun(ReLock reLock) {
			this.reLock = reLock;
		}

		// 判断是否停止
		private boolean isStop = false;
		// 是否停止成功
		private boolean stopSuccess = false;

		@Override
		public void run() {
			MDC.put("requestId", reLock.getName());
			logger.debug("{}启动了{}线程", reLock.getName(), Thread.currentThread().getName());
			int i = 0;
			while (!isStop) {
				try {
					i++;
					logger.debug("执行第{}次", i);
					Thread.sleep(halfLockTime);
					if (isStop) {
						logger.debug("守护线程isStop为True");
						stopSuccess = true;
						return;
					}

					// 如果是在锁的状态,就续命
					if (reLock.getLockStatus().equals(LockStatus.IsLook)) {
						// 延长生命!
						boolean lookSuccess = RedisTool.tryGetXXDistributedLock(jedis, lockKey, requestId, lockTime);

						if (!lookSuccess) {
							setStop();
							if (reLock.getLockStatus().equals(LockStatus.UnLock)) {
								logger.debug("守护线程续命不成功,因为已被解锁");
								return;
							}
							logger.debug("锁定的值不存在了");
							throw new RuntimeException("锁定的值不存在了");
						}
						logger.debug("lockKey={}延长生命!", lockKey);
					}
				} catch (InterruptedException e) {
					logger.debug("被强制退出阻塞了!");
				}
			}
			stopSuccess = true;
		}

		/**
		 * 设置停止
		 */
		public synchronized void setStop() {
			logger.debug("isStop被设置为true");
			isStop = true;
			// this.interrupt();
		}

		/**
		 * 是否停止成功
		 * 
		 * @return
		 */
		public Boolean getStopSuccess() {
			return stopSuccess;
		}
	}

	static enum LockStatus {
		IsLook, UnLock
	}

}
