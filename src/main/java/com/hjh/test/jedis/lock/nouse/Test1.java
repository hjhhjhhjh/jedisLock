package com.hjh.test.jedis.lock.nouse;

public class Test1 implements Runnable {
	static int sum;
	private SpinLock lock;

	public Test1(SpinLock lock) {
		this.lock = lock;
	}

	public static void main(String[] args) throws InterruptedException {
		SpinLock lock = new SpinLock();
		for (int i = 0; i < 100; i++) {
			Test1 test = new Test1(lock);
			Thread t = new Thread(test);
			t.start();
		}

		Thread.sleep(1000);
		System.out.println(sum);
	}

	@Override
	public void run() {
		this.lock.lock();
		sum++;
		this.lock.unLock();
	}
}


