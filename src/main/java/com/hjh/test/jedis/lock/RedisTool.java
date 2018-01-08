package com.hjh.test.jedis.lock;

import java.util.Collections;

import redis.clients.jedis.Jedis;

/**
 * 
 * @author HJH
 *
 */
public class RedisTool {

	private static final String LOCK_SUCCESS = "OK";
	private static final String SET_IF_NOT_EXIST = "NX";// 取NX，则只有当key不存在是才进行set
	private static final String SET_IF_EXIST = "XX";// 取XX，则只有当key已经存在时才进行set

	private static final String SET_WITH_EXPIRE_TIME = "PX";// EX代表秒，PX代表毫秒

	/**
	 * 尝试获取分布式锁,则只有当key不存在是才进行set
	 * 
	 * @param jedis
	 * @param lockKey
	 *            锁的key
	 * @param requestId
	 *            请求标识
	 * @param expireTime
	 *            超期时间
	 * @return 是否锁定成功
	 */
	public static boolean tryGetNXDistributedLock(Jedis jedis, String lockKey, String requestId, int expireTime) {
		return tryGetDistributedLock2(jedis, lockKey, requestId, expireTime, SET_IF_NOT_EXIST);

	}

	/**
	 * 尝试获取分布式锁,则只有当key已经存在时才进行set
	 * 
	 * @param jedis
	 * @param lockKey
	 *            锁的key
	 * @param requestId
	 *            请求标识
	 * @param expireTime
	 *            超期时间
	 * @return 是否锁定成功
	 */
	public static boolean tryGetXXDistributedLock(Jedis jedis, String lockKey, String requestId, int expireTime) {
		return tryGetDistributedLock2(jedis, lockKey, requestId, expireTime, SET_IF_EXIST);
	}

	
	private static boolean tryGetDistributedLock2(Jedis jedis, String lockKey, String requestId, int expireTime,
			String nxxx) {
		String result = jedis.set(lockKey, requestId, nxxx, SET_WITH_EXPIRE_TIME, expireTime);

		if (LOCK_SUCCESS.equals(result)) {
			return true;
		}
		return false;
	}

	private static final Long RELEASE_SUCCESS = 1L;

	/**
	 * 释放分布式锁
	 * 
	 * @param jedis
	 *            Redis客户端
	 * @param lockKey
	 *            锁
	 * @param requestId
	 *            请求标识
	 * @return 是否释放成功
	 */
	public static boolean releaseDistributedLock(Jedis jedis, String lockKey, String requestId) {

		String script = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
		Object result = jedis.eval(script, Collections.singletonList(lockKey), Collections.singletonList(requestId));

		if (RELEASE_SUCCESS.equals(result)) {
			return true;
		}
		return false;

	}

}
