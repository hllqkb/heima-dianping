# 黑马点评
### 优化以及技术要点和面试和技术图表
# 简历上展示黑马点评

## 项目描述

黑马点评项目是一个springboot开发的前后端分离项目，使用了redis集群、tomcat集群、MySQL集群提高服务性能。类似于大众点评，实现了短信登录、商户查询缓存、优惠卷秒杀、附近的商户、UV统计、用户签到、好友关注、达人探店  八个部分形成了闭环。其中重点使用了分布式锁实现了一人一单功能、项目中大量使用了Redis 的知识。

## 所用技术

*SpringBoot+nginx+MySql+Lombok+MyBatis-Plus+Hutool+Redis*

使用 Redis 解决了在集群模式下的 Session共享问题,使用拦截器实现用户的登录校验和权限刷新

基于Cache Aside模式解决数据库与缓存的一致性问题

使用 Redis 对高频访问的信息进行缓存，降低了数据库查询的压力,解决了缓存穿透、雪崩、击穿问题使用 Redis + Lua脚

本实现对用户秒杀资格的预检，同时用乐观锁解决秒杀产生的超卖问题

使用Redis分布式锁解决了在集群模式下一人一单的线程安全问题

基于stream结构作为消息队列,实现异步秒杀下单

使用Redis的 ZSet 数据结构实现了点赞排行榜功能,使用Set 集合实现关注、共同关注功能

# 黑马定评项目 亮点难点

## 使用Redis解决了在集群模式下的Session共享问题，使用拦截器实现了用户的登录校验和权限刷新

**为什么用Redis替代Session？**

使用Session时，根据客户端发送的session-id获取Session，再从Session获取数据，由于Session共享问题：多台Tomct并不共享session存储空间，当请求切换到不同tomcat服务时导致数据丢失的问题。

解决方法：用Redis代替Session存储User信息，注册用户时，会生成一个随机的Token作为Key值存放用户到Redis中。

**还有其他解决方法吗？**

基于 Cookie 的 Token 机制，不再使用服务器端保存 Session，而是通过客户端保存 Token（如 JWT）。
Token 包含用户的认证信息（如用户 ID、权限等），并通过签名验证其完整性和真实性。
每次请求，客户端将 Token 放在 Cookie 或 HTTP 头中发送到服务

**说说你的登录流程？**

![image-20250307131627121](https://cdn.jsdelivr.net/gh/KNeegcyao/picdemo/img/image-20250307131627121.png)

使用Redis代替session作为缓存，使用Token作为唯一的key值

**怎么使用拦截器实现这些功能？**

![image](https://github.com/user-attachments/assets/b9f35ed2-58fb-4e41-90b0-0d4ab00c5278)


系统中设置了两层拦截器：

第一层拦截器是做全局处理，例如获取Token，查询Redis中的用户信息，刷新Token有效期等通用操作。

第二层拦截器专注于验证用户登录的逻辑，如果路径需要登录，但用户未登录，则直接拦截请求。

**使用两层的原因？**

使用拦截器是因为，多个线程都需要获取用户，在想要方法之前统一做些操作，就需要用拦截器，还可以拦截没用登录的用户，但只有一层拦截器不是拦截所有请求，所有有些请求不会刷新Token时间，我们就需要再加一层拦截器，拦截所有请求，做到一直刷新。

*好处：*

职责分离：这种分层设计让每个拦截器的职责更加单一，代码更加清晰、易于维护

提升性能：如果直接在第一层拦截器处理登录验证，可能会对每个请求都进行不必要的检查。而第二层拦截器仅在“需要登录的路径”中生效，可以避免不必要的性能开销。

灵活性：这种机制方便扩展，不需要修改第一层的全局逻辑。

复用 ThreadLocal 数据：第一层拦截器已经将用户信息保存到 ThreadLocal 中，第二层拦截器可以直接使用这些数据，而不需要重复查询 Redis 或其他数据源。

## 基于Cache Aside模式解决数据库与缓存的一致性问题

**怎么保证缓存更新策略的高一致性需求？**

我们使用的时Redisson实现的读写锁，再读的时候添加共享锁，可以保证读读不互斥，读写互斥。我们更新数据的时候，添加排他锁，他是读写，读读都互斥，这样就能保证在写数据的同时，是不会让其他线程读数据的，避免了脏数据。读方法和写方法是同一把锁。

## 使用 Redis 对高频访问的信息进行缓存，降低了数据库查询的压力,解决了缓存穿透、雪崩、击穿问题

**什么是缓存穿透，怎么解决？**

![image](https://github.com/user-attachments/assets/30fdea5c-f0ef-46f8-ab6f-cbfd65e78072)

*定义：* 1.用户请求的id在缓存中不存在。
2.恶意用户伪造不存在的id发起请求。

大量并发去访问一个数据库不存在的数据，由于缓存中没有该数据导致大量并发查询数据库，这个现象叫缓存穿透。
缓存穿透可以造成数据库瞬间压力过大，连接数等资源用完，最终数据库拒绝连接不可用。

*解决方法：*

1.对请求增加校验机制

eg:字段id是长整型，如果发来的不是长整型则直接返回

2.使用布隆过滤器

![image](https://github.com/user-attachments/assets/86652912-0713-426c-a842-0366067c4225)

为了避免缓存穿透我们需要缓存预热将要查询的课程或商品信息的id提前存入布隆过滤器，添加数据时将信息的id也存入过滤器，当去查询一个数据时先在布隆过滤器中找一下如果没有到到就说明不存在，此时直接返回。

3.缓存空值或特殊值（本项目应用）

![image](https://github.com/user-attachments/assets/9d185eb4-4080-4bad-8d41-f4ba3a670372)

请求通过了第一步的校验，查询数据库得到的数据不存在，此时我们仍然去缓存数据，缓存一个空值或一个特殊值的数据。
但是要注意：如果缓存了空值或特殊值要设置一个短暂的过期时间。

**什么是缓存雪崩，怎么解决？**
![image](https://github.com/user-attachments/assets/758bf96d-af23-4d89-91de-71f96c001be6)


*定义：* 缓存雪崩是缓存中大量key失效后当高并发到来时导致大量请求到数据库，瞬间耗尽数据库资源，导致数据库无法使用。

造成缓存雪崩问题的原因是是大量key拥有了相同的过期时间，比如对课程信息设置缓存过期时间为10分钟，在大量请求同时查询大量的课程信息时，此时就会有大量的课程存在相同的过期时间，一旦失效将同时失效，造成雪崩问题。

*解决方法：*

1、使用同步锁控制查询数据库的线程

使用同步锁控制查询数据库的线程，只允许有一个线程去查询数据库，查询得到数据后存入缓存。

```java
synchronized(obj){
  //查询数据库
  //存入缓存
}
```

2、对同一类型信息的key设置不同的过期时间

通常对一类信息的key设置的过期时间是相同的，这里可以在原有固定时间的基础上加上一个随机时间使它们的过期时间都不相同。

```java
   //设置过期时间300秒
  redisTemplate.opsForValue().set("course:" + courseId, JSON.toJSONString(coursePublish),300+new Random().nextInt(100), TimeUnit.SECONDS);
```

3、缓存预热

不用等到请求到来再去查询数据库存入缓存，可以提前将数据存入缓存。使用缓存预热机制通常有专门的后台程序去将数据库的数据同步到缓存。

**什么是缓存击穿，怎么解决？**

![image](https://github.com/user-attachments/assets/ae5f4706-29ac-4d18-9816-4f7f4e3660f1)


*定义：* 缓存击穿是指大量并发访问同一个热点数据，当热点数据失效后同时去请求数据库，瞬间耗尽数据库资源，导致数据库无法使用。
比如某手机新品发布，当缓存失效时有大量并发到来导致同时去访问数据库。

*解决方法：*

1.基于互斥锁解决

![image](https://github.com/user-attachments/assets/6c70bc97-3ebd-4b64-a43b-a2cf8c0564fb)



互斥锁（时间换空间）

优点：内存占用小，一致性高，实现简单

缺点：性能较低，容易出现死锁

这里使用Redis中的setnx指令实现互斥锁，只有当值不存在时才能进行set操作

锁的有效期更具体业务有关，需要灵活变动，一般锁的有效期是业务处理时长10~20倍

线程获取锁后，还需要查询缓存（也就是所谓的双检），这样才能够真正有效保障缓存不被击穿

2.基于逻辑过期方式

![image](https://github.com/user-attachments/assets/a5e7ef0f-df12-4612-8ed9-fb622e5bfd70)

逻辑过期（空间换时间）

优点：性能高

缺点：内存占用较大，容易出现脏读

·注意：逻辑过期一定要先进行数据预热，将我们热点数据加载到缓存中

适用场景

商品详情页、排行榜等热点数据场景。

数据更新频率低，但访问量大的场景。

总结：两者相比较，互斥锁更加易于实现，但是容易发生死锁，且锁导致并行变成串行，导致系统性能下降，逻辑过期实现起来相较复杂，且需要耗费额外的内存，但是通过开启子线程重建缓存，使原来的同步阻塞变成异步，提高系统的响应速度，但是容易出现脏读

**为什么重建子线程,作用是什么？**

开启子线程重建缓存的作用在于提高系统的响应速度，避免因缓存击穿导致的数据库压力过大，同时保障系统在高并发场景下的稳定性，但开启子线程重建缓存可能引入数据不一致（脏读）问题

具体原因：

1. 提高系统响应速度

同步阻塞的缺点： 在缓存失效时，传统方案通常会同步查询数据库更新缓存，这会导致用户请求被阻塞，特别是在高并发环境下可能出现大量线程等待，影响系统响应性能。

子线程重建缓存的优势：

主线程只需返回缓存中的旧数据，避免阻塞用户请求。
重建缓存的任务交由后台线程执行，提高用户体验。

2. 减少数据库压力

缓存击穿问题： 当热点数据过期时，多个线程同时访问数据库，可能导致数据库压力骤增，甚至崩溃。

子线程异步重建缓存：

将数据库查询集中到一个后台线程中执行，避免多个线程同时查询数据库。
即便在缓存击穿的情况下，也不会对数据库造成过大的负载。

3. 提高系统吞吐量

同步更新的瓶颈： 如果所有线程都等待缓存更新完成，系统吞吐量会因阻塞而降低。

异步重建的优化：

主线程可以快速返回旧数据，提升并发处理能力。

数据更新操作与用户请求分离，减少了阻塞等待。

4. 减少热点数据竞争

高并发场景下的竞争： 热点数据被大量请求时，多个线程可能同时触发缓存更新逻辑，产生资源竞争。

单子线程更新的效果：

后台线程独占更新任务，避免多线程竞争更新缓存。

配合分布式锁机制，可以有效减少竞争开销。

5. 提升系统的稳定性

数据库保护：

异步更新缓存，减缓数据库的瞬时高并发压力。

在极端情况下，即使缓存更新失败，系统仍能通过返回旧数据保持基本的服务能力。

熔断机制结合：

子线程的异步更新可以结合熔断、降级等机制，当更新任务失败时，系统可快速响应并记录失败日志以便后续处理。

·适用场景

热点数据： 商品详情页、排行榜等访问量极高的场景。

高并发场景： 秒杀、抢购活动中，需要频繁访问热点数据。

容忍短暂数据不一致的场景： 如排行榜数据的延迟更新对用户体验影响较小。

## 使用 Redis + Lua脚本实现对用户秒杀资格的预检，同时用乐观锁解决秒杀产生的超卖问题

**什么是超卖问题，怎么解决？**

![image](https://github.com/user-attachments/assets/6136ae13-f7b9-43cc-9a2e-83e23d4d1e49)

超卖问题：并发多线程问题，当线程1查询库存后，判断前，又有别的线程来查询，从而造成判断错误，超卖。

解决方式：

```
悲观锁： 添加同步锁，让线程串行执行
      优点：简单粗暴
      缺点：性能一般
```

```
乐观锁：不加锁，再更新时判断是否有其他线程在修改

      优点：性能好
      缺点：存在成功率低的问题(该项目在超卖问题中，不在需要判断数据查询时前后是否一致，直接判读库存>0;有的项目里不是库存，只能判断数据有没有变化时，还可以用分段锁，将数据分到10个表，同时十个去抢)
```

**说一下乐观锁和悲观锁？**

悲观锁：悲观锁总是假设最坏的情况，认为共享资源每次被访问的时候就会出现问题(比如共享数据被修改)，所以每次在获取资源操作的时候都会上锁，这样其他线程想拿到这个资源就会阻塞直到锁被上一个持有者释放。也就是说，共享资源每次只给一个线程使用，其它线程阻塞，用完后再把资源转让给其它线程。

乐观锁:乐观锁总是假设最好的情况，认为共享资源每次被访问的时候不会出现问题，线程可以不停地执行，无需加锁也无需等待，只是在提交修改的时候去验证对应的资源（也就是数据）是否被其它线程修改了（具体方法可以使用版本号机制或 CAS 算法）。

悲观锁通常多用于写比较多的情况（多写场景，竞争激烈），这样可以避免频繁失败和重试影响性能，悲观锁的开销是固定的。不过，如果乐观锁解决了频繁失败和重试这个问题的话（比如LongAdder），也是可以考虑使用乐观锁的，要视实际情况而定。

乐观锁通常多用于写比较少的情况（多读场景，竞争较少），这样可以避免频繁加锁影响性能。不过，乐观锁主要针对的对象是单个共享变量（参考java.util.concurrent.atomic包下面的原子变量类）。

**你使用的什么？**

使用的是乐观锁CAS算法。CAS是一个原子操作，底层依赖于一条CPU的原子指令。

设计三个参数：

- V:要更新的变量值
- E：预期值
- N：拟入的新值

当且仅当V的值等于E时，CAS通过原子方式用新值N来更新V的值。如果不等，说明已经有其他线程更新了V，则当前线程放弃更新。

<img src="https://cdn.jsdelivr.net/gh/KNeegcyao/picdemo/img/image-20250307150305076.png" alt="image-20250307150305076" style="zoom: 33%;" />

从业务的角度看，只要库存数还有，就能执行这个操作，所以where条件设置为stock>0

![image-20250307150937348](https://cdn.jsdelivr.net/gh/KNeegcyao/picdemo/img/image-20250307150937348.png)

## 使用Redis分布式锁解决了在集群模式下一人一单的线程安全问题

为了防止批量刷券，添加逻辑：根据优惠券id和用户id查询订单，如果不存在，则创建。

在集群模式下，加锁只是对该JVM给当前这台服务器的请求的加锁，而集群是多台服务器，所以要使用分布式锁，满足集群模式下多进程可见并且互斥的锁。

**Redis分布式锁实现思路？**
我使用的Redisson分布式锁，他能做到可重入，可重试

*可重入*:同一线程可以多次获取同一把锁，可以避免死锁，用hash结构存储。

​           大key是根据业务设置的，小key是线程唯一标识，value值是当前重入次数。

<img src="https://cdn.jsdelivr.net/gh/KNeegcyao/picdemo/img/image-20241205233234744.png" alt="image-20241205233234744" style="zoom: 50%;" />

*可重试*：Redisson手动加锁，可以控制锁的失效时间和等待时间，当锁住的一个业务并没有执行完成的时候，Redisson会引入一个Watch Dog看门狗机制。就是说，每隔一段时间就检查当前事务是否还持有锁。如果持有，就增加锁的持有时间。当业务执行完成之后，需要使用释放锁就可以了。还有个好处就是，在`高并发`下，一个业务有可能会执行很快。客户1持有锁的时候，客户2来了以后并不会马上拒绝，他会自旋不断尝试获取锁。如果客户1释放之后，客户2可以立马持有锁，性能也能得到提升。





![](https://cdn.jsdelivr.net/gh/KNeegcyao/picdemo/img/image-20250307153056365.png)

*主从一致性*：连锁(multiLock)-不再有主从节点，都获取成功才能获取锁成功，有一个节点获取锁不成功就获取锁失败

一个宕机了，还有两个节点存活，锁依旧有效，可用性随节点增多而增强。如果想让可用性更强，也可以给多个节点建立主从关系，做主从同步，但不会有主从一致问题，当新线程来新的主节点获取锁，由于另外两个主节点依然有锁，不会出现锁失效问题吗，所以不会获取成功。

![image-20250307153809096](https://cdn.jsdelivr.net/gh/KNeegcyao/picdemo/img/image-20250307153809096.png)

[另一篇文章详细了解Redisson](https://kneegcyao.github.io/2024/12/05/Redisson/)

![image-20241207183707979](https://cdn.jsdelivr.net/gh/KNeegcyao/picdemo/img/image-20241207183707979.png)

## 基于stream结构作为消息队列,实现异步秒杀下单

**为什么用异步秒杀?**

![image-20250307160040968](https://cdn.jsdelivr.net/gh/KNeegcyao/picdemo/img/image-20250307160040968.png)

我们用jmeter测试，发现高并发下异常率高，吞吐量低，平均耗时高

整个业务流程是串行执行的，查询优惠券，查询订单，减库存，创建订单这四步都是走的数据库，mysql本身并发能力就较少，还有读写操作，还加了分布式锁，整个业务耗时长，并发能力弱。

**怎么进行优化？**

![image-20241207220614455](https://cdn.jsdelivr.net/gh/KNeegcyao/picdemo/img/image-20241207220614455.png)

我们分成两个线程，我们将耗时较短的逻辑判断放到Redis中，例如：库存是否充足，是否一人一单这样的操作，只要满足这两条操作，那我们是一定可以下单成功的，不用等数据真的写进数据库，我们直接告诉用户下单成功就好了，将信息引入异步队列记录相关信息，然后后台再开一个线程，后台线程再去慢慢执行队列里的消息，这样我们就能很快的完成下单业务。

![img](https://cdn.jsdelivr.net/gh/KNeegcyao/picdemo/img/e342f782da8bd166aae355478e72fd06269fdcd127c7df90e342500ee9318476.jpg)

- 当用户下单之后，判断库存是否充足，只需要取Redis中根据key找对应的value是否大于0即可，如果不充足，则直接结束。如果充足，则在Redis中判断用户是否可以下单，如果set集合中没有该用户的下单数据，则可以下单，并将userId和优惠券存入到Redis中，并且返回0，整个过程需要保证是原子性的，所以我们要用Lua来操作，同时由于我们需要在Redis中查询优惠券信息，所以在我们新增秒杀优惠券的同时，需要将优惠券信息保存到Redis中
- 完成以上逻辑判断时，我们只需要判断当前Redis中的返回值是否为0，如果是0，则表示可以下单，将信息保存到queue中去，然后返回，开一个线程来异步下单，其阿奴单可以通过返回订单的id来判断是否下单成功

**说说stream类型消息队列？**

使用的是消费者组模式（`Consumer Group`）

- 消费者组(Consumer Group)：将多个消费者划分到一个组中，监听同一个队列，具备以下特点
    1. 消息分流
        - 队列中的消息会分留给组内的不同消费者，而不是重复消费者，从而加快消息处理的速度
    2. 消息标识
        - 消费者会维护一个标识，记录最后一个被处理的消息，哪怕消费者宕机重启，还会从标识之后读取消息，确保每一个消息都会被消费
    3. 消息确认
        - 消费者获取消息后，消息处于pending状态，并存入一个pending-list，当处理完成后，需要通过XACK来确认消息，标记消息为已处理，才会从pending-list中移除

*基本语法：*

- 创建消费者组

  ```java
  XGROUP CREATE key groupName ID [MKSTREAM]
  ```

    - key: 队列名称
    - groupName: 消费者组名称
    - ID: 起始ID标识，$代表队列中的最后一个消息，0代表队列中的第一个消息
    - MKSTREAM: 队列不存在时自动创建队列

- 删除指定的消费者组

  ```bash
  XGROUP DESTORY key groupName
  ```

- 给指定的消费者组添加消费者

  ```bash
  XGROUP CREATECONSUMER key groupName consumerName
  ```

- 删除消费者组中指定的消费者

  ```bash
  XGROUP DELCONSUMER key groupName consumerName
  ```

- 从消费者组中读取消息

  ```bash
  XREADGROUP GROUP group consumer [COUNT count] [BLOCK milliseconds] [NOACK] STREAMS key [keys ...] ID [ID ...]
  ```

    - group: 消费者组名称
    - consumer: 消费者名，如果消费者不存在，会自动创建一个消费者
    - count: 本次查询的最大数量
    - BLOCK milliseconds: 当前没有消息时的最大等待时间
    - NOACK: 无需手动ACK，获取到消息后自动确认（一般不用，我们都是手动确认）
    - STREAMS key: 指定队列名称
    - ID: 获取消息的起始ID
        - `>`：从下一个未消费的消息开始(pending-list中)
        - 其他：根据指定id从pending-list中获取已消费但未确认的消息，例如0，是从pending-list中的第一个消息开始



*基本思路：*

```java
while(true){
    // 尝试监听队列，使用阻塞模式，最大等待时长为2000ms
    Object msg = redis.call("XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 >")
    if(msg == null){
        // 没监听到消息，重试
        continue;
    }
    try{
        //处理消息，完成后要手动确认ACK，ACK代码在handleMessage中编写
        handleMessage(msg);
    } catch(Exception e){
        while(true){
            //0表示从pending-list中的第一个消息开始，如果前面都ACK了，那么这里就不会监听到消息
            Object msg = redis.call("XREADGROUP GROUP g1 c1 COUNT 1 STREAMS s1 0");
            if(msg == null){
                //null表示没有异常消息，所有消息均已确认，结束循环
                break;
            }
            try{
                //说明有异常消息，再次处理
                handleMessage(msg);
            } catch(Exception e){
                //再次出现异常，记录日志，继续循环
                log.error("..");
                continue;
            }
        }
    }
}
```
**XREADGROUP命令的特点？**

1. 消息可回溯
2. 可以多消费者争抢消息，加快消费速度
3. 可以阻塞读取
4. 没有消息漏读风险
5. 有消息确认机制，保证消息至少被消费一次

---
**✅改进：** 使用RabbitMQ更适合。相关面试题可以看这个[相关文章](https://kneegcyao.github.io/posts/93c6a719.html);

**1.在`application.yml`中配置RabbitMQ**

```yaml
  spring:
    rabbitmq:
      host: localhost
      port: 5672
      username: guest
      password: guest
```

- **2. 声明队列和交换机**
    - **正常队列和交换机的绑定：**
      commonExchange ("Common") → 使用路由键 "CQ" 绑定到 queueC ("CQ")。
    - **死信队列和交换机的绑定：**
      deadLetterExchange ("Dead-letter") → 使用路由键 "DLQ" 绑定到 deadLetterQueueD ("DLQ")。
    - **普通队列到死信交换机的关系（死信机制）：**
      queueC ("CQ") 配置了：
        - 死信交换机为 **Dead-letter**；
        - 死信路由键为 **"DLQ"**；
        - TTL 为 10 秒。
    - 当 **queueC** 中的消息超过 TTL 或触发其它死信条件后，这些消息将被自动发送到 **deadLetterExchange**，再由 **deadLetterExchange** 根据 **"DLQ"** 路由键路由到 **deadLetterQueueD**。

```java
@Configuration
public class QueueConfig {

    // 普通交换机名称
    public static final String COMMON_EXCHANGE = "Common";
    // 死信交换机名称
    public static final String DEAD_DEAD_LETTER_EXCHANGE = "Dead-letter";
    // 普通队列名称
    public static final String QUEUE_C = "CQ";
    // 死信队列名称
    public static final String DEAD_LETTER_QUEUE_D = "DLQ";

    /**
     * 声明普通交换机
     * 
     * @return DirectExchange
     */
    @Bean("commonExchange")
    public DirectExchange commonExchange(){
        return new DirectExchange(COMMON_EXCHANGE);
    }

    /**
     * 声明死信交换机
     * 
     * @return DirectExchange
     */
    @Bean("deadLetterExchange")
    public DirectExchange deadLetterExchange(){
        return new DirectExchange(DEAD_DEAD_LETTER_EXCHANGE);
    }

    /**
     * 声明普通队列C, 并绑定死信交换机及设置消息TTL
     * 
     * 设置说明：
     * - x-dead-letter-exchange: 配置消息过期后转发的死信交换机名称
     * - x-dead-letter-routing-key: 配置转发到死信交换机时使用的路由键，此处与死信队列绑定时的路由键一致（"DLQ"）
     * - x-message-ttl: 消息存活时间（此处设置为10000毫秒，即10秒）
     *
     * @return Queue
     */
    @Bean("queueC")
    public Queue queueC(){
        HashMap<String, Object> arguments = new HashMap<>();
        // 消息在队列中存活10秒后失效，进入死信队列
        arguments.put("x-message-ttl", 10000);
        // 配置死信交换机
        arguments.put("x-dead-letter-exchange", DEAD_DEAD_LETTER_EXCHANGE);
        // 配置死信路由键，绑定到死信队列时使用
        arguments.put("x-dead-letter-routing-key", "DLQ");

        return QueueBuilder.durable(QUEUE_C)
                           .withArguments(arguments)
                           .build();
    }

    /**
     * 声明死信队列D
     * 
     * @return Queue
     */
    @Bean("deadLetterQueueD")
    public Queue deadLetterQueueD(){
        return QueueBuilder.durable(DEAD_LETTER_QUEUE_D)
                           .build();
    }

    /**
     * 普通队列C与普通交换机Common绑定
     *
     * 当消息发送到交换机Common，并使用路由键 "CQ" 时，
     * 消息将被路由到队列CQ。
     *
     * @param queueC 普通队列
     * @param commonExchange 普通交换机
     * @return Binding
     */
    @Bean
    public Binding bindingQueueCToCommonExchange(@Qualifier("queueC") Queue queueC,
                                                 @Qualifier("commonExchange") DirectExchange commonExchange) {
        return BindingBuilder.bind(queueC).to(commonExchange).with("CQ");
    }

    /**
     * 死信队列D与死信交换机Dead-letter绑定
     *
     * 当普通队列CQ中的消息由于TTL过期或其他原因被转为死信后，
     * 消息会转发到死信交换机Dead-letter，并使用路由键 "DLQ"，
     * 从而被路由到死信队列DLQ。
     *
     * @param deadLetterQueueD 死信队列
     * @param deadLetterExchange 死信交换机
     * @return Binding
     */
    @Bean
    public Binding bindingDeadLetterQueueDToDeadLetterExchange(@Qualifier("deadLetterQueueD") Queue deadLetterQueueD,
                                                               @Qualifier("deadLetterExchange") DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueueD).to(deadLetterExchange).with("DLQ");
    }
}

```

**3.发送者**

```java
       VoucherOrder order = new VoucherOrder();
        order.setId(orderId);
        order.setUserId(userId);
        order.setVoucherId(voucherId);
        // 你可以用 JSON，也可以用序列化
        // 增加消息发送的异常处理
        //放入mq
        String jsonStr = JSONUtil.toJsonStr(order);
        try {
            rabbitTemplate.convertAndSend("Common","CQ",jsonStr );
        } catch (Exception e) {
            log.error("发送 RabbitMQ 消息失败，订单ID: {}", orderId, e);
            throw new RuntimeException("发送消息失败");
        }
        // 3. 返回订单号给前端（实际下单异步处理）
        return Result.ok(orderId);
    }
```

**4.接受者**

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class SeckillVoucherListener {

    @Resource
    SeckillVoucherServiceImpl seckillVoucherService;
    
    @Resource
    VoucherOrderServiceImpl voucherOrderService;

    /**
     * 普通队列消费者：监听队列 "CQ"
     *
     * 消息从普通队列 "CQ" 进入后进行转换处理，保存订单，同时数据库秒杀库存减一
     *
     * @param message RabbitMQ消息
     * @param channel 消息通道
     * @throws Exception 异常处理
     */
    @RabbitListener(queues = "CQ")
    public void receivedC(Message message, Channel channel) throws Exception {
        String msg = new String(message.getBody());
        log.info("普通队列:");
        VoucherOrder voucherOrder = JSONUtil.toBean(msg, VoucherOrder.class);
        log.info(voucherOrder.toString());
        voucherOrderService.save(voucherOrder);  // 保存订单到数据库

        // 秒杀业务：库存减一操作
        Long voucherId = voucherOrder.getVoucherId();
        seckillVoucherService.update()
                .setSql("stock = stock - 1") // set stock = stock - 1
                .eq("voucher_id", voucherId)
                .gt("stock", 0)             // where voucher_id = ? and stock > 0
                .update();
    }

    /**
     * 死信队列消费者：监听队列 "DLQ"
     *
     * 消息从死信队列 "DLQ" 进入后进行相同的处理，
     * 适用于消息因过期或其它原因进入死信队列时的处理逻辑
     *
     * @param message RabbitMQ消息
     * @throws Exception 异常处理
     */
    @RabbitListener(queues = "DLQ")
    public void receivedDLQ(Message message) throws Exception {
        log.info("死信队列:");
        String msg = new String(message.getBody());
        VoucherOrder voucherOrder = JSONUtil.toBean(msg, VoucherOrder.class);
        log.info(voucherOrder.toString());
        voucherOrderService.save(voucherOrder);  // 保存订单到数据库

        // 秒杀业务：库存减一操作
        Long voucherId = voucherOrder.getVoucherId();
        seckillVoucherService.update()
                .setSql("stock = stock - 1") // set stock = stock - 1
                .eq("voucher_id", voucherId)
                .gt("stock", 0)             // where voucher_id = ? and stock > 0
                .update();
    }
}

```
---


## 使用Redis的 ZSet 数据结构实现了点赞排行榜功能,使用Set 集合实现关注、共同关注功能

**什么是ZSet?**

Zset，即有序集合（Sorted Set），是 Redis 提供的一种复杂数据类型。Zset 是 set 的升级版，它在 set 的基础上增加了一个权重参数 score，使得集合中的元素能够按 score 进行有序排列。

在 Zset 中，集合元素的添加、删除和查找的时间复杂度都是 O(1)。这得益于 Redis 使用的是一种叫做跳跃列表（skiplist）的数据结构来实现 Zset。

**为什么使用ZSet数据结构？**

一人只能点一次赞，对于点赞这种高频变化的数据，如果我们使用MySQL是十分不理智的，因为MySQL慢、并且并发请求MySQL会影响其它重要业务，容易影响整个系统的性能，继而降低了用户体验。

![image-20241208154509134](https://cdn.jsdelivr.net/gh/KNeegcyao/picdemo/img/image-20241208154509134.png)

Zset 的主要特性包括：

1.  唯一性：和 set 类型一样，Zset 中的元素也是唯一的，也就是说，同一个元素在同一个 Zset 中只能出现一次。
2.  排序：Zset 中的元素是有序的，它们按照 score 的值从小到大排列。如果多个元素有相同的 score，那么它们会按照字典序进行排序。
3.  自动更新排序：当你修改 Zset 中的元素的 score 值时，元素的位置会自动按新的 score 值进行调整。



**点赞**

用ZSet中的add方法增添，时间戳作为score（zadd key value score)

用ZSet中的score方法，来判断是否存在

```java
 @Override
    public Result updateLike(Long id){
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2.判断当前用户有没有点赞
        String key=BLOG_LIKED_KEY+id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if(score==null) {
            //3.如果未点赞，可以点赞
            //3.1.数据库点赞数+1
            boolean isSuccess = update().setSql("liked=liked+1").eq("id", id).update();
            //3.2.保存用户到redis的set集合  zadd key value score
            if(isSuccess){
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }
        }else {
            //4.如果已经点赞，取消点赞
            //4.1.数据库点赞数-1
            boolean isSuccess = update().setSql("liked=liked-1").eq("id", id).update();
            if(isSuccess) {
                //4.2.将用户从set集合中移除
                stringRedisTemplate.opsForZSet().remove(key,userId.toString());
            }
        }
        return Result.ok();
    }
```

**共同关注**

通过Set中的intersect方法求两个key的交集

```java
 @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //获取登录用户
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        //1.判断关注还是取关
        if(isFollow) {
            //2.关注
            Follow follow = new Follow();
            follow.setFollowUserId(followUserId);
            follow.setUserId(userId);
            boolean isSuccess = save(follow);
            if(isSuccess){
                //把关注用户的id，放入redis的set集合 sadd userId followUserId
                stringRedisTemplate.opsForSet().add(key,followUserId.toString());
            }
        }else {
            //3.取关
            boolean isSuccess = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId)
                    .eq("follow_user_id", followUserId));
            //移除
            if(isSuccess){
                stringRedisTemplate.opsForSet().remove(key,followUserId.toString());
            }
        }
        return Result.ok();
    }

```



```java
@Override
    public Result followCommons(Long id) {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        //求交集
        String key2 = "follows:" + id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        if(intersect==null||intersect.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //解析出id
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());

        //查询用户
        List<UserDTO> userDTOS = userService
                .listByIds(ids).stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(userDTOS);

    }
```
## 代码使用说明
项目代码包含2个分支：
- master : 主分支，包含完整版代码，作为大家的编码参考使用
- init : 初始化分支，实战篇的初始代码，建议大家以这个分支作为自己开发的基础代码
## 1.下载
克隆完整项目
```git
git clone https://gitee.com/huyi612/hm-dianping.git
```
切换分支
```git
git checkout init
```

## 2.常见问题
部分同学直接使用了master分支项目来启动，控制台会一直报错:
```
NOGROUP No such key 'stream.orders' or consumer group 'g1' in XREADGROUP with GROUP option
```
这是因为我们完整版代码会尝试访问Redis，连接Redis的Stream。建议同学切换到init分支来开发，如果一定要运行master分支，请先在Redis运行一下命令：
```text
XGROUP CREATE stream.orders g1 $ MKSTREAM
```