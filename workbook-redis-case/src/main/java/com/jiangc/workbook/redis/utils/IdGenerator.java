package com.jiangc.workbook.redis.utils;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.ArrayList;
import java.util.List;

/**   
 * @Title: 分布式id生成器
 * @Description: 
 * 利用redis的lua脚本执行功能，在每个节点上通过lua脚本生成唯一ID。 生成的ID是64位的：
 * 使用41 bit来存放时间，精确到毫秒，可以使用41年。
 * 使用12 bit来存放逻辑分片ID，最大分片ID是4095
 * 使用10 bit来存放自增长ID，意味着每个节点，每毫秒最多可以生成1024个ID
 * 生成最终ID: ((second * 1000 + microSecond / 1000) << (12 + 10)) + (shardId << 10) + seq;
 * ID的数据结构算法参考自https://github.com/hengyunabc/redis-id-generator
 */
public class IdGenerator {


	private RedisTemplate<String,Object> redisTemplate;

	public IdGenerator(RedisTemplate redisTemplate){
		this.redisTemplate = redisTemplate;
	}

	public void setRedisTemplate(RedisTemplate RedisTemplate){
		this.redisTemplate = redisTemplate;
	}

	/**
	 * 获取秒，毫秒，自增数
	 * @param jedisPoolIdx
	 * @param miliSecondKey
	 * @return 秒，毫秒，自增数
	 */
	@SuppressWarnings({ "unchecked", "deprecation" })
	private List<Long> generatIdMeta(int jedisPoolIdx,String miliSecondKey){

		int startStep=jedisPoolIdx;//起始步长
		int step=20;//总步长
		String luaScript=""
				+"\r\nlocal step = "+step+";"//步长
				+"\r\nlocal key = '"+miliSecondKey+"';"
				+"\r\nlocal count;"
				+"\r\nrepeat"
				+"\r\n  count = tonumber(redis.call('INCRBY', key, step));"
				+"\r\nuntil count < (1024 - step)"
				+"\r\nif count == step then"
				+"\r\n  redis.call('PEXPIRE', key, 1);"
				+"\r\nend"
				+"\r\nlocal now = redis.call('TIME');"
				+"\r\nreturn {tonumber(now[1]), tonumber(now[2]), count + "+startStep+"}";
		List<String> keys = new ArrayList<String>();
		RedisScript<List> script = new DefaultRedisScript<List>(
				luaScript, List.class);
		List<Long> res = redisTemplate
				.execute(script, keys, new Object[] {});
		return res;
	}

	/***
	 * 生成一个永不重复的分布式自增长id（长度为63bit），前41bit位是时间（精确到毫秒），后22bit位是自增数
	 * @param key
	 * @return
	 */
	@SuppressWarnings({ "unchecked", "deprecation" })
	public long generatIdByIncr(String key){
		key="_incr_"+key;
		int maxMantissa=4194304;//2的22次方
		String luaScript=""
				+"\r\nlocal count = tonumber(redis.call('INCR', '"+key+"'));"
				+"\r\nif count >= "+(maxMantissa-1)+" then"
				+"\r\n  redis.call('DEL', '"+key+"');"
				+"\r\nend"
				+"\r\nlocal now = redis.call('TIME');"
				+"\r\nreturn {tonumber(now[1]), tonumber(now[2]), count}";
		List<String> keys = new ArrayList<String>();
		RedisScript<List> script = new DefaultRedisScript<List>(
				luaScript, List.class);
		List<Long> res = redisTemplate
				.execute(script, keys, new Object[] {});
		long second=res.get(0),microSecond=res.get(1),seq=res.get(2);
		return buildIdBit(second, microSecond, seq);
		
	}	
	@SuppressWarnings({ "unchecked", "deprecation" })
	public long generatIdByIncr(String key, int expire){
		key="_incr_"+key;
		String luaScript=""
				+"\r\nlocal count = tonumber(redis.call('INCR', '"+key+"'));"
				+"\r\nredis.call('EXPIRE', '"+key+"', "+expire+");"				
				+"\r\nreturn count";
		List<String> keys = new ArrayList<String>();
		RedisScript<Long> script = new DefaultRedisScript<Long>(
				luaScript, Long.class);
		Long res = redisTemplate
				.execute(script, keys, new Object[] {});

		return res;
	}		
	/**
	 * 生成带分逻辑分片id的全局ID
	 * @param
	 * @param shardId
	 * @return
	 */
	public long generatShardId(String shardName, long shardId){
		int startStep=(int)shardId%20;
		shardId=shardId%4096;
		List<Long> meta=generatIdMeta(startStep, "_gsid_"+shardName+"_"+shardId);
		return buildId64Bit(meta.get(0), meta.get(1), meta.get(2), shardId);
	}
	
	/**
	 * 使用41 bit来存放时间，精确到毫秒，可以使用41年。
	 * 使用12 bit来存放逻辑分片ID，最大分片ID是4095
	 * 使用10 bit来存放自增长ID，意味着每个节点，每毫秒最多可以生成1024个ID
	 * 生成最终ID: ((second * 1000 + microSecond / 1000) << (12 + 10)) + (shardId << 10) + seq;
	 * @return
	 */
	private static long buildId64Bit(long second, long microSecond,long seq, long shardId) {
		long miliSecond = (second * 1000 + microSecond / 1000);
		return (miliSecond << (12 + 10)) + (shardId << 10) + seq;
	}
	/**
	 * 使用41 bit来存放时间，精确到毫秒，可以使用41年。
	 * 剩下的 bit来存放序列
	 * 生成最终ID: ((second * 1000 + microSecond / 1000) << 10) + seq;
	 * @return
	 */
	private static long buildIdBit(long second, long microSecond,long seq) {
		long miliSecond = (second * 1000 + microSecond / 1000);
		return (miliSecond <<10) + seq;
	}

	/**
	 * 解析id
	 * @param id
	 * @return
	 */
	protected static List<Long> parseId(long id) {
		long miliSecond = id >>> 22;
		// 2 ^ 12 = 0xFFF
		long shardId = (id & (0xFFF << 10)) >> 10;
		long seq = id & 0x3FF;

		List<Long> re = new ArrayList<Long>(4);
		re.add(miliSecond);
		re.add(shardId);
		re.add(seq);
		return re;
	}
	/**
	 * 解析id中的逻辑分片信息
	 * 使用12 bit来存放逻辑分片ID，最大分片ID是4095
	 * @param id
	 * @return
	 */
	public static long parseShardId(long id) {
		long shardId = (id & (0xFFF << 10)) >> 10;
		return shardId;
	}
	
	public String generatorSerialNo(){
		String dateStr = DateUtils.getDate("yyyyMMdd");
		long seriaNo = generatIdByIncr(dateStr,24*60*60);
		String seriaNumber = dateStr + String.format("%05d", seriaNo);
		return seriaNumber;
	}
	
	public String generatorBaiduReqId(){
		String dateStr = DateUtils.getDate("yyyyMMdd");
		long seriaNo = generatIdByIncr("BD"+dateStr,24*60*60);
		String seriaNumber = dateStr + String.format("%05d", seriaNo);
		return seriaNumber;
	}

}
