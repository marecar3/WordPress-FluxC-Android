package org.wordpress.android.fluxc.persistence

import com.wellsql.generated.QuickStartModelTable
import com.yarolegovich.wellsql.WellSql
import org.wordpress.android.fluxc.model.QuickStartModel
import org.wordpress.android.fluxc.store.QuickStartStore.QuickStartTask
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class QuickStartSqlUtils
@Inject constructor() {
    fun getDoneCount(siteId: Long): Int {
        return WellSql.select(QuickStartModel::class.java)
                .where().beginGroup()
                .equals(QuickStartModelTable.SITE_ID, siteId)
                .equals(QuickStartModelTable.IS_DONE, true)
                .endGroup().endWhere()
                .asModel.size
    }

    fun getShownCount(siteId: Long): Int {
        return WellSql.select(QuickStartModel::class.java)
                .where().beginGroup()
                .equals(QuickStartModelTable.SITE_ID, siteId)
                .equals(QuickStartModelTable.IS_SHOWN, true)
                .endGroup().endWhere()
                .asModel.size
    }

    private fun getTask(siteId: Long, task: QuickStartTask): QuickStartModel? {
        return WellSql.select(QuickStartModel::class.java)
                .where().beginGroup()
                .equals(QuickStartModelTable.SITE_ID, siteId)
                .equals(QuickStartModelTable.TASK_NAME, task.toString())
                .endGroup().endWhere()
                .asModel.firstOrNull()
    }

    fun hasDoneTask(siteId: Long, task: QuickStartTask): Boolean {
        return getTask(siteId, task)?.isDone ?: false
    }

    fun hasShownTask(siteId: Long, task: QuickStartTask): Boolean {
        return getTask(siteId, task)?.isShown ?: false
    }

    private fun insertOrUpdateQuickStartModel(newModel: QuickStartModel) {
        val oldModel = getTask(newModel.siteId, QuickStartTask.fromString(newModel.taskName))
        oldModel?.let {
            WellSql.update(QuickStartModel::class.java)
                    .whereId(it.id)
                    .put(newModel, UpdateAllExceptId(QuickStartModel::class.java))
                    .execute()
            return
        }
        WellSql.insert(newModel).execute()
    }

    fun setDoneTask(siteId: Long, task: QuickStartTask, isDone: Boolean) {
        val model = QuickStartModel()
        model.siteId = siteId
        model.taskName = task.toString()
        model.isDone = isDone
        insertOrUpdateQuickStartModel(model)
    }

    fun setShownTask(siteId: Long, task: QuickStartTask, isShown: Boolean) {
        val model = QuickStartModel()
        model.siteId = siteId
        model.taskName = task.toString()
        model.isShown = isShown
        insertOrUpdateQuickStartModel(model)
    }
}
