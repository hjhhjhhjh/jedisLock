package com.hjh.test.jedis.lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.ShardedJedis;

/**
 * 
 * @author HJH
 *
 */
public class ApplicationStart {
	private final static Logger logger = LoggerFactory.getLogger(ApplicationStart.class);

	public static void main(String[] args) throws Throwable {

		// Jedis jedis = new Jedis("192.168.2.247", 6379);
		Thread.currentThread().setName("main thread");
		JedisPoolConfig config = new JedisPoolConfig();
		config.setMaxTotal(2000);
		config.setMaxIdle(10);
		config.setMaxWaitMillis(30000);
		config.setTestOnBorrow(true);
		config.setTestOnReturn(true);
		String ip = "192.168.2.247";
		int port = 6379;
		int timeOut = 2000;
		int db = 11;
		JedisPool jp = new JedisPool(config, ip, port, timeOut, null, db);

		// jedis.set("ttt", "123", "NX", "PX", 10000);
		// String str = jedis.get("ttt");
		// logger.info(str);

		ReLockFactory reLockFactory = new ReLockFactory();

		for (int i = 0; i < 2000; i++) {
			logger.info("启动线程" + i);

			final int k = i;
			Thread t = new Thread(new Runnable() {
				@Override
				public void run() {
					logger.info(Thread.currentThread().getName() + ":" + "线程" + k + "开始获取资源");
					Jedis jedis = jp.getResource();
					logger.info(Thread.currentThread().getName() + ":" + "线程" + k + "获取到资源");

					int lockWaitTime = 10;
					try (ReLock reLock = reLockFactory.getResource(jedis, "ttt111222" + k, lockWaitTime * 1000)) {
						boolean isLock = reLock.tryLock();

						if (!isLock) {
							throw new Exception(lockWaitTime + "秒锁不到啊啊啊啊啊");
						}

						int i = 1;
						logger.info(Thread.currentThread().getName() + ":" + "开始睡" + i + "秒");
						Thread.sleep(1000 * i);
						logger.info(Thread.currentThread().getName() + ":" + "睡完" + i + "秒");
						// reLock.unlock();

					} catch (Exception e) {
						e.printStackTrace();
					} finally {
						// TODO: handle finally clause
					}
					// logger.info(Thread.currentThread().getName() + ":" +
					// "jedis.close()");
					// jedis.close();
				}
			});
			t.start();
			logger.info(Thread.currentThread().getName() + "已启动");
			Thread.sleep(10);

		}
		//Thread.sleep(11000);
		reLockFactory.close();

	}
}
