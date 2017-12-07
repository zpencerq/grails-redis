/* Copyright (C) 2011 SpringSource
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package grails.plugin.redis

import com.google.gson.Gson
import grails.util.Holders
import org.codehaus.groovy.grails.commons.GrailsApplication
import redis.clients.jedis.Jedis
import redis.clients.jedis.Pipeline
import redis.clients.jedis.Transaction
import redis.clients.jedis.exceptions.JedisConnectionException

class RedisService {

    public static final int NO_EXPIRATION_TTL = -1
    public static final int KEY_DOES_NOT_EXIST = -2  // added in redis 2.8

    def redisPool
    GrailsApplication grailsApplication = Holders.grailsApplication

    boolean transactional = false

    RedisService withConnection(String connectionName){
        if(grailsApplication.mainContext.containsBean("redisService${connectionName.capitalize()}")){
            return (RedisService)grailsApplication.mainContext.getBean("redisService${connectionName.capitalize()}")
        }
        if (log.errorEnabled) log.error("Connection with name redisService${connectionName.capitalize()} could not be found, returning default redis instead")
        return this
    }

    def withPipeline(Closure closure, Boolean returnAll=false) {
        withRedis { Jedis redis ->
            Pipeline pipeline = redis.pipelined()
            closure(pipeline)
            returnAll ? pipeline.syncAndReturnAll() : pipeline.sync()
        }
    }

    def withOptionalPipeline(Closure clos, Boolean returnAll = false) {
        withOptionalRedis { Jedis redis ->
            if (redis) {
                Pipeline pipeline = redis.pipelined()
                clos(pipeline)
                returnAll ? pipeline.syncAndReturnAll() : pipeline.sync()
            }
            else {
                return clos()
            }

        }
    }

    def withTransaction(Closure closure) {
        withRedis { Jedis redis ->
            Transaction transaction = redis.multi()
            try {
                closure(transaction)
            } catch(Exception exception) {
                transaction.discard()
                throw exception
            }

            transaction.exec()
        }
    }

    def methodMissing(String name, args) {
        if (log.debugEnabled) log.debug "methodMissing $name"
        withRedis { Jedis redis ->
            redis.invokeMethod(name, args)
        }
    }

    void propertyMissing(String name, Object value) {
        withRedis { Jedis redis ->
            redis.set(name, value.toString())
        }
    }

    Object propertyMissing(String name) {
        withRedis { Jedis redis ->
            redis.get(name)
        }
    }

    def withRedis(Closure closure) {
        Jedis redis = redisPool.resource
        try {
            def ret = closure(redis)
            redisPool.returnResource(redis)
            return ret
        } catch(JedisConnectionException jce) {
            redisPool.returnBrokenResource(redis)
            throw jce
        } catch(Exception e) {
            redisPool.returnResource(redis)
            throw e
        }
    }

    /**
     * An implementation of withRedis that suppresses JedisConnectException to support the memoization model
     * @param clos
     * @return
     */
    def withOptionalRedis(Closure clos) {
        Jedis redis
        try {
			redis = redisPool.resource
        }
        catch (JedisConnectionException jce) {
            log.info('Unreachable redis store trying to retrieve redis resource.  Please check redis server and/or config!')
        }

        try {
            def ret = clos(redis)
            if (redis) redisPool.returnResource(redis)
            ret
        }
        catch (JedisConnectionException jce) {
            log.error('Unreachable redis store trying to return redis pool resource.  Please check redis server and/or config!', jce)
            if (redis) redisPool.returnBrokenResource(redis)
        }
        catch (Throwable t) {
            if (redis) redisPool.returnResource(redis)
            throw t
        }
    }

    def memoize(String key, Integer expire, Closure closure) {
        memoize(key, [expire: expire], closure)
    }

    // SET/GET a value on a Redis key
    def memoize(String key, Map options = [:], Closure closure) {
        if (log.debugEnabled) log.debug "using key $key"
        def result = withOptionalRedis { Jedis redis ->
            if (redis) return redis.get(key)
        }

        if(!result) {
            if (log.debugEnabled) log.debug "cache miss: $key"
            result = closure()
            if(result) withOptionalRedis { Jedis redis ->
                if (redis) {
                    if(options?.expire) {
                        redis.setex(key, options.expire, result as String)
                    } else {
                        redis.set(key, result as String)
                    }
                }
            }
        } else {
            if (log.debugEnabled) log.debug "cache hit : $key = $result"
        }
        result
    }

    def memoizeHash(String key, Integer expire, Closure closure) {
        memoizeHash(key, [expire: expire], closure)
    }

    def memoizeHash(String key, Map options = [:], Closure closure) {
        def hash = withOptionalRedis { Jedis redis ->
            if (redis) return redis.hgetAll(key)
        }

        if(!hash) {
            if (log.debugEnabled) log.debug "cache miss: $key"
            hash = closure()
            if(hash) withOptionalRedis { Jedis redis ->
                if (redis) {
                    redis.hmset(key, hash)
                    if(options?.expire) redis.expire(key, options.expire)
                }
            }
        } else {
            if (log.debugEnabled) log.debug "cache hit : $key = $hash"
        }
        hash
    }

    def memoizeHashField(String key, String field, Integer expire, Closure closure) {
        memoizeHashField(key, field, [expire: expire], closure)
    }

    // HSET/HGET a value on a Redis hash at key.field
    // if expire is not null it will be the expire for the whole hash, not this value
    // and will only be set if there isn't already a TTL on the hash
    def memoizeHashField(String key, String field, Map options = [:], Closure closure) {
        def result = withOptionalRedis { Jedis redis ->
            if (redis) return redis.hget(key, field)
        }

        if(!result) {
            if (log.debugEnabled) log.debug "cache miss: $key.$field"
            result = closure()
            if(result) withOptionalRedis { Jedis redis ->
                if (redis) {
                    redis.hset(key, field, result as String)
                    if(options?.expire && redis.ttl(key) == NO_EXPIRATION_TTL) redis.expire(key, options.expire)
                }
            }
        } else {
            if (log.debugEnabled) log.debug "cache hit : $key.$field = $result"
        }
        result
    }

    def memoizeScore(String key, String member, Integer expire, Closure closure) {
        memoizeScore(key, member, [expire: expire], closure)
    }

    // set/get a 'double' score within a sorted set
    // if expire is not null it will be the expire for the whole zset, not this value
    // and will only be set if there isn't already a TTL on the zset
    def memoizeScore(String key, String member, Map options = [:], Closure closure) {
        def score = withOptionalRedis { Jedis redis ->
            if (redis) redis.zscore(key, member)
        }

        if(!score) {
            if (log.debugEnabled) log.debug "cache miss: $key.$member"
            score = closure()
            if(score) withOptionalRedis { Jedis redis ->
                if (redis) {
                    redis.zadd(key, score, member)
                    if(options?.expire && redis.ttl(key) == NO_EXPIRATION_TTL) redis.expire(key, options.expire)
                }
            }
        } else {
            if (log.debugEnabled) log.debug "cache hit : $key.$member = $score"
        }
        score
    }

    List memoizeDomainList(Class domainClass, String key, Integer expire, Closure closure) {
        memoizeDomainList(domainClass, key, [expire: expire], closure)
    }

    List memoizeDomainList(Class domainClass, String key, Map options = [:], Closure closure) {
        List<String> idList = getIdListFor(key)
        if(idList) return hydrateDomainObjectsFrom(domainClass, idList)

        def domainList = withOptionalRedis { Jedis redis ->
            closure(redis)
        }

        saveIdListTo(key, domainList, options.expire)

        domainList
    }

    List<String> memoizeDomainIdList(Class domainClass, String key, Integer expire, Closure closure) {
        memoizeDomainIdList(domainClass, key, [expire: expire], closure)
    }

    // used when we just want the list of Ids back rather than hydrated objects
    List<String> memoizeDomainIdList(Class domainClass, String key, Map options = [:], Closure closure) {
        List<String> idList = getIdListFor(key)
        if(idList) return idList

        def domainList = closure()

        saveIdListTo(key, domainList, options.expire)

        getIdListFor(key)
    }

    protected List<String> getIdListFor(String key) {
        List<String> idList = withOptionalRedis { Jedis redis ->
            if (redis) return redis.lrange(key, 0, -1)
        }

        if(idList) {
            if (log.debugEnabled) log.debug "$key cache hit, returning ${idList.size()} ids"
            List<String> idStringList = idList*.toString()
            return idStringList
        }
    }

    protected void saveIdListTo(String key, List domainList, Integer expire = null) {
        if (log.debugEnabled) log.debug "$key cache miss, memoizing ${domainList?.size() ?: 0} ids"
        withOptionalPipeline { pipeline ->
            if (pipeline) {
                for(domain in domainList) {
                    pipeline.rpush(key, domain.id as String)
                }
                if(expire) pipeline.expire(key, expire)
            }
        }
    }

    protected List hydrateDomainObjectsFrom(Class domainClass, List<String> idList) {
        if(domainClass && idList) {
            //return domainClass.findAllByIdInList(idList, [cache: true])
            return idList.collect { id -> domainClass.load(id) }
        }
        []
    }

    def memoizeDomainObject(Class domainClass, String key, Integer expire, Closure closure) {
        memoizeDomainObject(domainClass, key, [expire: expire], closure)
    }

    // closure can return either a domain object or a String id of a domain object
    // it will be persisted into redis as the String
    def memoizeDomainObject(Class domainClass, String key, Map options = [:], Closure closure) {
        String domainId = withOptionalRedis { redis ->
            redis?.get(key)?.toString()
        }
        if(!domainId) domainId = persistDomainId(closure()?.id as String, key, options.expire)
        domainClass.load(domainId)
    }

    String persistDomainId(String domainId, String key, Integer expire) {
        if(domainId) {
            withOptionalPipeline { pipeline ->
                if (pipeline) {
                    pipeline.set(key, domainId)
                    if(expire) pipeline.expire(key, expire)
                }
            }
        }
        domainId
    }

    def memoizeObject(Class clazz, String key, Integer expire, Closure closure) {
        memoizeObject(clazz, key, [expire: expire], closure)
    }

    def memoizeObject(Class clazz, String key, Map options = [:], Closure closure) {
        Gson gson = new Gson()

        String memoizedJson = memoize(key, options) { ->
            def original = closure()
            if (original == null && options.cacheNull == false) return null
            gson.toJson(original)
        }

        gson.fromJson((String)memoizedJson, clazz)
    }

    // deletes all keys matching a pattern (see redis "keys" documentation for more)
    // OK for low traffic methods, but expensive compared to other redis commands
    // perf test before relying on this rather than storing your own set of keys to 
    // delete
    void deleteKeysWithPattern(keyPattern) {
        if (log.infoEnabled) log.info("Cleaning all redis keys with pattern  [${keyPattern}]")
        withRedis { Jedis redis ->
            String[] keys = redis.keys(keyPattern)
            if(keys) redis.del(keys)
        }
    }

    def memoizeList(String key, Integer expire, Closure closure) {
        memoizeList(key, [expire: expire], closure)
    }

    def memoizeList(String key, Map options = [:], Closure closure) {
        List list = withOptionalRedis { Jedis redis ->
            if (redis) return redis.lrange(key, 0, -1)
        }

        if(!list) {
            if (log.debugEnabled) log.debug "cache miss: $key"
            list = closure()
            if(list) withOptionalPipeline { pipeline ->
                if (pipeline) {
                    for(obj in list) { pipeline.rpush(key, obj) }
                    if(options?.expire) pipeline.expire(key, options.expire)
                }
            }
        } else {
            if (log.debugEnabled) log.debug "cach hit: $key"
        }
        list
    }

    def memoizeSet(String key, Integer expire, Closure closure) {
        memoizeSet(key, [expire: expire], closure)
    }

    def memoizeSet(String key, Map options = [:], Closure closure) {
        def set = withOptionalRedis { Jedis redis ->
            if (redis) return redis.smembers(key)
        }

        if(!set) {
            if (log.debugEnabled) log.debug "cache miss: $key"
            set = closure()
            if(set) withOptionalPipeline { pipeline ->
                if (pipeline) {
                    for(obj in set) { pipeline.sadd(key, obj) }
                    if(options?.expire) pipeline.expire(key, options.expire)
                }
            }
        } else {
            if (log.debugEnabled) log.debug "cache hit: $key"
        }
        set
    }
    // should ONLY Be used from tests unless we have a really good reason to clear out the entire redis db
    def flushDB() {
        if (log.warnEnabled) log.warn('flushDB called!')
        withRedis { Jedis redis ->
            redis.flushDB()
        }
    }
}