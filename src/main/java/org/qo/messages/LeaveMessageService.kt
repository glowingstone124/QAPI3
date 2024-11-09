package org.qo.messages

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.qo.orm.LeaveMessage
import org.qo.orm.LeaveMessageORM
import org.springframework.stereotype.Service

@Service
class LeaveMessageService {
	val leaveMessageORM = LeaveMessageORM()
	suspend fun insert(from: String, to: String, message: String): LeaveMessageStatus {
		val currentMessageList = leaveMessageORM.getDefinedSenderMessages(from);
		if(currentMessageList.size > 5){
			return LeaveMessageStatus.TOO_MANY_SEND
		}
		leaveMessageORM.insertMessage(from, to, message)
		return LeaveMessageStatus.SUCCESS
	}
	@OptIn(DelicateCoroutinesApi::class)
	suspend fun getTargetReceivers(receiver: String): List<LeaveMessage>{
		val result = leaveMessageORM.getDefinedReceiverMessages(receiver)
		GlobalScope.launch {
			result.forEach {
				leaveMessageORM.deleteSpecifiedSenderMessages(it.from, it.to, it.message)
			}
		}
		return result
	}

}
enum class LeaveMessageStatus(val code: Int) {
	SUCCESS(0),
	DISALLOWED(1),
	TOO_MANY_SEND(2),
	TOO_MANY_RECEIVED(3);


	fun toInt(): Int {
		return code
	}

	companion object {
		fun fromInt(value: Int): LeaveMessageStatus {
			return LeaveMessageStatus.entries.find { it.code == value }
				?: throw IllegalArgumentException("No LeaveMessageStatus with code $value")
		}
	}
}
