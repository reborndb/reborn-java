#Reborn-java - Java client for RebornDB
Reborn-java is a java client for RebornDB built on [Jodis](https://github.com/wandoulabs/codis/tree/master/extern/jodis) which is implemented by [Apache9](https://github.com/Apache9).

##Features
- Use a round robin policy to balance load to multiple reborn-proxies.
- Detect proxy online and offline automatically.

##How to use

```java
JedisResourcePool jedisPool = new RoundRobinJedisPool("zkserver:2181", 30000, "/zk/reborn/db_xxx/proxy", new JedisPoolConfig());
try (Jedis jedis = jedisPool.getResource()) {
    jedis.set("foo", "bar");
    String value = jedis.get("foo");
}
```

##Note
JDK7 is required to build and use reborn-java. If you want to use reborn-java with JDK6, you can copy the source files to your project, replace ThreadLocalRandom in BoundedExponentialBackoffRetryUntilElapsed and JDK7 specified grammar(maybe, not sure), and then compile with JDK6.

