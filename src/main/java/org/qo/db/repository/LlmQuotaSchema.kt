package org.qo.db.repository

object LlmQuotaSchema {
	const val ZOMBIE_PENDING_TIMEOUT_SECONDS = 30 * 60L

	val SCHEMA = listOf(
		"CREATE TABLE IF NOT EXISTS ai_quota_account (user_id BIGINT PRIMARY KEY,paid_credits INT NOT NULL DEFAULT 0)",
		"CREATE TABLE IF NOT EXISTS ai_weekly_usage (user_id BIGINT NOT NULL,period VARCHAR(10) NOT NULL,used INT NOT NULL,PRIMARY KEY(user_id,period))",
		"CREATE TABLE IF NOT EXISTS ai_free_budget (period VARCHAR(10) PRIMARY KEY,actual_cost DECIMAL(20,12) NOT NULL,reserved_cost DECIMAL(20,12) NOT NULL)",
		"CREATE TABLE IF NOT EXISTS ai_quota_reservation (request_key VARCHAR(128) PRIMARY KEY,user_id BIGINT NOT NULL,period VARCHAR(10) NOT NULL,weekly_limit INT NOT NULL,weekly_units INT NOT NULL,paid_units INT NOT NULL,mode VARCHAR(16) NOT NULL,provider VARCHAR(64) NOT NULL,model VARCHAR(128) NOT NULL,status VARCHAR(16) NOT NULL,created_at BIGINT NOT NULL,reset_at BIGINT NOT NULL,budget_period VARCHAR(10) NOT NULL,free_reserved DECIMAL(20,12) NOT NULL)",
		"CREATE TABLE IF NOT EXISTS ai_system_usage (id VARCHAR(64) PRIMARY KEY,source VARCHAR(64) NOT NULL,provider VARCHAR(64) NOT NULL,model VARCHAR(128) NOT NULL,budget_period VARCHAR(10) NOT NULL,estimated_cost DECIMAL(20,12) NOT NULL,actual_cost DECIMAL(20,12),status VARCHAR(16) NOT NULL,created_at BIGINT NOT NULL)",
		"CREATE TABLE IF NOT EXISTS ai_usage (request_key VARCHAR(128) PRIMARY KEY,user_id BIGINT NOT NULL,conversation_id VARCHAR(128),input_tokens BIGINT NOT NULL,output_tokens BIGINT NOT NULL,cached_tokens BIGINT NOT NULL,reasoning_tokens BIGINT,actual_cost DECIMAL(20,12) NOT NULL,charged_units INT NOT NULL,weekly_units INT NOT NULL,paid_units INT NOT NULL,created_at BIGINT NOT NULL)",
		"CREATE TABLE IF NOT EXISTS ai_credit_ledger (id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT NOT NULL,reference_id VARCHAR(128) NOT NULL,delta INT NOT NULL,kind VARCHAR(16) NOT NULL,created_at BIGINT NOT NULL)",
		"CREATE TABLE IF NOT EXISTS ai_purchase_intent (id VARCHAR(64) PRIMARY KEY,user_id BIGINT NOT NULL,sku_id VARCHAR(64) NOT NULL,amount DECIMAL(10,2) NOT NULL,credits INT NOT NULL,status VARCHAR(16) NOT NULL,created_at BIGINT NOT NULL)",
		"CREATE TABLE IF NOT EXISTS ai_afdian_pending_order (out_trade_no VARCHAR(64) PRIMARY KEY,status VARCHAR(16) NOT NULL,attempts INT NOT NULL,next_attempt_at BIGINT NOT NULL,last_error VARCHAR(64),created_at BIGINT NOT NULL)",
		"CREATE TABLE IF NOT EXISTS ai_payment_order (id BIGINT AUTO_INCREMENT PRIMARY KEY,provider VARCHAR(32) NOT NULL,out_trade_no VARCHAR(64) NOT NULL,intent_id VARCHAR(64) NOT NULL UNIQUE,user_id BIGINT NOT NULL,amount DECIMAL(10,2) NOT NULL,credits INT NOT NULL,created_at BIGINT NOT NULL,UNIQUE(provider,out_trade_no))"
	)
}
