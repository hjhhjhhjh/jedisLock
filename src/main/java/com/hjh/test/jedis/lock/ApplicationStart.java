package com.hjh.test.jedis.lock;

import redis.clients.jedis.Jedis;

/**
 * 
 * @author HJH
 *
 */
public class ApplicationStart {
	public static void main(String[] args) throws Throwable {
		Jedis jedis = new Jedis("192.168.2.247", 6379);

		// jedis.set("ttt", "123", "NX", "PX", 10000);
		// String str = jedis.get("ttt");
		// System.out.println(str);

		for (int i = 0; i < 2; i++) {
			System.out.println("启动线程" + i);
			Thread t = new Thread(new Runnable() {
				@Override
				public void run() {
					try (ReLock reLock = new ReLock(jedis, "ttt111222", 2000)) {
						boolean isLock = reLock.tryLock();

						if (!isLock) {
							throw new Exception("3秒锁不到啊啊啊啊啊");
						}

						System.out.println("开始睡30秒");
						Thread.sleep(20000);
						System.out.println("睡完30秒");

					} catch (Exception e) {
						e.printStackTrace();
					} finally {
						// TODO: handle finally clause
					}

				}
			});
			t.start();
			
			Thread.sleep(500);
		}

	}
}
