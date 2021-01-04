package example

import java.util.concurrent.locks.{Lock, ReadWriteLock}

class LockExample {

  /**
    * Execute the given function inside the lock
    */
  def inLock[T](lock: Lock)(fun: => T): T = {
    lock.lock()
    try {
      fun
    } finally {
      lock.unlock()
    }
  }

  /**
    * 在读锁中执行 fun
    * @param lock
    * @param fun
    * @tparam T
    * @return
    */
  def inReadLock[T](lock: ReadWriteLock)(fun: => T): T = inLock[T](lock.readLock)(fun)

  /**
    * 在写锁中执行 fun
    * @param lock
    * @param fun
    * @tparam T
    * @return
    */
  def inWriteLock[T](lock: ReadWriteLock)(fun: => T): T = inLock[T](lock.writeLock)(fun)


}
