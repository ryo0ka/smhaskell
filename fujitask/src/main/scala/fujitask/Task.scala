package fujitask

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/** This is a rough English translation and explanation of:
  * https://github.com/hexx/fujitask-simple
  *
  * This [[Task]] trait aims to provide two functionality:
  *   1. make it easy to define database transactions by binding queries
  *   2. determines a transaction's access permission level at compile time
  *
  * 1.
  * What "query" and "transaction" means here:
  * A query is a message to a database: "create", "get", etc.
  * A transaction is an ordered list of those queries to be executed in one session;
  * for example, 1. create a user named "Dan", 2. read its ID number.
  *
  * Why we combine queries into a transaction:
  * Executing multiple queries in one session improves database traffic in some cases.
  * PofEAA introduces the benefit: http://martinfowler.com/eaaCatalog/unitOfWork.html
  *
  * Monadic approach to defining a transaction:
  * A transaction is essentially one query that compounds multiple queries.
  * A transaction has an empty state: no queries given to execute.
  * Well, these make it a Monad. Yay :)
  * The Monadic value is a (compound of) query, which is a function from a database to whatever value.
  * The bind function combines those functions just like Reader Monad and State Monad do.
  *
  * [[Task]] as a transaction Monad:
  * A Task instance represents both a query and a transaction (as a transaction is a compounded query.)
  * The `execute` method defines the Monadic value: a function from a database to any values (`A`).
  * The `flatMap` method is the bind function to combine queries in a new Task and return it.
  * The `run` method executes the transaction following a given instruction.
  *
  * The example of the usage can be seen in `domain\service\MessageService.scala` in the `domain` package.
  *
  * 2.
  * Two permission levels and runtime error:
  * In a master/slave structure database, queries that modify the database must not be sent to a slave database.
  * Such queries are labeled as a Read/Write query so that a slave database would recognize and block them beforehand.
  * Only queries labeled as a Read query can be sent to a slave database.
  *
  * Determining transaction's permission level:
  * A transaction's permission level is determined by its compounding queries':
  * for example, if one is Read/Write and another is Read, then the transaction is Read/Write.
  *
  * [[Task]] determines transaction permission level at compile time:
  * Each Task instance is tagged by a type that represents a permission level (namely `Resource`).
  * If the Read/Write is defined to be a subtype of the Read in the type system and used for `Resource`,
  * the upper bound of `Resource` (`-Resource`) and the `flatMap` method's restriction (`ExtendedResource <: Resource`)
  * guarantee the type conversion (binding) to follow this rule:
  *   Task[Read     ] * Task[Read     ] = Task[Read]
  *   Task[Read     ] * Task[ReadWrite] = Task[ReadWrite]
  *   Task[ReadWrite] * Task[Read     ] = Task[ReadWrite]
  *   Task[ReadWrite] * Task[ReadWrite] = Task[ReadWrite]
  * Then the above permission determination completes at compile time. No more runtime error!
  * [[Transaction]] defines the Read/Write inheriting the Read permission.
  *
  * @tparam Resource determines this Task's permission level.
  * @tparam A determines the type of value that this Task will receive when executed.
 */
trait Task[-Resource, +A] { self =>
  def execute(resource: Resource)(implicit ec: ExecutionContext): Future[A]

  def flatMap[ExtendedResource <: Resource, B](f: A => Task[ExtendedResource, B]): Task[ExtendedResource, B] =
    new Task[ExtendedResource, B] {
      def execute(resource: ExtendedResource)(implicit ec: ExecutionContext): Future[B] =
        self.execute(resource).map(f).flatMap(_.execute(resource))
    }

  def map[B](f: A => B): Task[Resource, B] = flatMap(a => Task(f(a)))

  def run[ExtendedResource <: Resource]()(implicit runner: TaskRunner[ExtendedResource]): Future[A] = {
    runner.run(self)
  }
}

object Task {
  def apply[Resource, A](a: => A): Task[Resource, A] =
    new Task[Resource, A] {
      def execute(resource: Resource)(implicit executor: ExecutionContext): Future[A] =
        Future(a)
    }
}

trait TaskRunner[Resource] {
  def run[A](task: Task[Resource, A]): Future[A]
}
