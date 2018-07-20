package com.hjh.test.jedis.lock;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import redis.clients.jedis.Jedis;

/**
 * 
 * @author HJH
 *
 */
public class ReLock implements AutoCloseable {
	// 记录本地所有被锁的key,左右类似二级缓存
	private static final Set<String> SYSTEM_LOCAL_LOCK_SET = ConcurrentHashMap.<String>newKeySet();

	// 锁定时间20秒
	private static final int LOCK_TIME = 20000;

	// 守护线程循环执行时间
	private static final int HALF_LOCK_TIME = LOCK_TIME / 2;

	// 线程循环等待时间
	private static final int THREAD_WAIT_TIME = 100;

	private static final ExecutorService executorService = Executors.newCachedThreadPool();

	// ThreadLocal<HashMap<String, GuardianThread>> tLocal = new
	// ThreadLocal<>();

	private GuardianRun gRun;
	private Jedis jedis;
	private String lockKey;
	private int waitTime;

	public ReLock(Jedis jedis, String lockKey, int waitTime) {
		this.jedis = jedis;
		this.lockKey = lockKey;
		this.waitTime = waitTime;
	}

	private String requestId = UUID.randomUUID().toString();

	public boolean tryLock() {
		// 设置停止时间
		long stopTime = System.currentTimeMillis() + waitTime;

		// 设置本地缓存锁住key
		boolean isSetSystemLocalSuccess = SYSTEM_LOCAL_LOCK_SET.add(lockKey);
		// 检测本地是否已经有被锁.如果有,就循环等待,直到超过等待时间;如果没有,就调用redis判断有没有被其它服务器锁了.
		if (!isSetSystemLocalSuccess) {
			while (SYSTEM_LOCAL_LOCK_SET.contains(lockKey)) {
				System.out.println("key被其它线程锁住了");

				// 如果超过等待时间,就返回false
				if (System.currentTimeMillis() > stopTime) {
					return false;
				}
				// 睡一会儿再来循环
				try {
					Thread.sleep(THREAD_WAIT_TIME);
				} catch (InterruptedException e) {
					e.printStackTrace();// TODO:需要处理吗?
				}
			}
		}

		// 获取锁,如果没有成功获取就继续获取,如果没获取成功,就循环等待,直到超过等待时间
		while (!RedisTool.tryGetNXDistributedLock(jedis, lockKey, requestId, LOCK_TIME)) {
			System.out.println("key被其它应用锁住了");

			// 如果超过等待时间,就返回false
			if (System.currentTimeMillis() > stopTime) {
				SYSTEM_LOCAL_LOCK_SET.remove(lockKey);
				return false;
			}
			// 睡一会儿再来循环
			try {
				Thread.sleep(THREAD_WAIT_TIME);
			} catch (InterruptedException e) {
				e.printStackTrace();// TODO:需要处理吗?
			}
		}
		// 创建自延寿命守护线程
		gRun = new GuardianRun();
		// 启动守护线程
		executorService.execute(gRun);

		return true;
	}

	/**
	 * 解锁
	 * 
	 * @return 是否解锁成功
	 */
	private boolean unlock() {
		// TODO:如果t有抛出了上面那个RuntimeException("锁定的值不存在了");怎么处理?

		System.out.println("执行解锁!");
		if (gRun != null) {
			// 停止守护
			gRun.setStop();

			while (!gRun.getStopSuccess()) {
				// 睡一会儿再来循环
				try {
					Thread.sleep(THREAD_WAIT_TIME);
				} catch (InterruptedException e) {
					e.printStackTrace();// TODO:需要处理吗?
				}
			}
		}

		// TODO:头痛,不知道要先删本地缓存再删远程还是反过来,是否需要判断结果......
		boolean isLocalUnlock = SYSTEM_LOCAL_LOCK_SET.remove(lockKey);

		// 调用Redis解锁
		boolean isUnlock = RedisTool.releaseDistributedLock(jedis, lockKey, requestId);
		System.out.println("解锁结果为:" + isUnlock);
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
			throw new RuntimeException("解锁竟然失败了,是不是有其它操作把key给搞掉了?");
		}
	}

	/**
	 * 守护线程
	 * 
	 * @author HJH
	 *
	 */
	class GuardianRun implements Runnable {
		// 判断是否停止
		private boolean isStop = false;
		// 是否停止成功
		private boolean stopSuccess = false;

		@Override
		public void run() {
			while (!isStop) {
				try {
					Thread.sleep(HALF_LOCK_TIME);

					if (isStop) {
						stopSuccess = true;
						return;
					}

					// 延长生命!
					boolean lookSuccess = RedisTool.tryGetXXDistributedLock(jedis, lockKey, requestId, LOCK_TIME);
					if (!lookSuccess) {
						setStop();
						throw new RuntimeException("锁定的值不存在了");
					}

					System.out.println("延长生命!");
				} catch (InterruptedException e) {
					System.out.println("被强制退出阻塞了!");
				}
			}
			stopSuccess = true;
		}

		/**
		 * 设置停止
		 */
		public synchronized void setStop() {
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

}
