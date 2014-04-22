/*
 * Copyright (C) 2003-2014 eXo Platform SAS.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, see<http://www.gnu.org/licenses/>.
 */
package org.exoplatform.forum.ext.activity;

import java.util.Queue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.exoplatform.container.PortalContainer;
import org.exoplatform.container.component.RequestLifeCycle;
import org.exoplatform.container.xml.InitParams;
import org.exoplatform.forum.common.InitParamsValue;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.social.core.activity.model.ExoSocialActivity;
import org.picocontainer.Startable;

public class ForumTaskManager implements Startable {
  private static final Log    LOG                = ExoLogger.getExoLogger(ForumTaskManager.class);
  private static final String   PERIOD_TIME_KEY      = "periodTime";
  private static final String   MAX_PERSIST_SIZE = "maxPersistSize";

  private final ScheduledExecutorService scheduler     = Executors.newScheduledThreadPool(1);
  private Queue<Task<ForumActivityContext>> tasks = null;
  private static long   INTERVAL         = 5000l;
  private static int    MAX_SIZE_PERSIST = 10;
  private boolean isDone = true;

  public ForumTaskManager(InitParams params) {
    INTERVAL = InitParamsValue.getLong(params, PERIOD_TIME_KEY, INTERVAL);
    MAX_SIZE_PERSIST = InitParamsValue.getInteger(params, MAX_PERSIST_SIZE, MAX_SIZE_PERSIST);
  }

  @Override
  public void start() {
    //
    makeInterval();
  }

  @Override
  public void stop() {
    isDone = false;
  }

  private void makeInterval() {
    //
    scheduler.scheduleWithFixedDelay(new Runnable() {
      @Override
      public void run() {
        if (!isCommit(true)) {
          return;
        }
        try {
          RequestLifeCycle.begin(PortalContainer.getInstance());
          persist();
        } finally {
          RequestLifeCycle.end();
        }
      }
    }, 30000, INTERVAL, TimeUnit.MILLISECONDS);
  }

  public void addTask(Task<ForumActivityContext> task) {
    if(tasks == null){
      tasks = new LinkedBlockingQueue<Task<ForumActivityContext>>();
    }
    tasks.add(task);
    //
    if (isCommit(false)) {
      persist();
    }
  }
  
  private boolean isCommit(boolean forceCommit) {
    if (tasks == null) {
      return false;
    }
    if (forceCommit && tasks.size() > MAX_SIZE_PERSIST) {
      return true;
    }
    return isDone;
  }

  private Queue<Task<ForumActivityContext>> popTasks() {
    Queue<Task<ForumActivityContext>> tmp = tasks;
    tasks = null;
    Queue<Task<ForumActivityContext>> processTasks = new LinkedBlockingQueue<Task<ForumActivityContext>>();
    for (Task<ForumActivityContext> forumTask : tmp) {
      if (!processTasks.contains(forumTask)) {
        processTasks.add(forumTask);
      }
    }
    //
    return processTasks;
  }
  
  private void persist() {
    //
    isDone = false;
    try {
      //
      Queue<Task<ForumActivityContext>> tasks = popTasks();
      
      Task<ForumActivityContext> task;
      while ((task = tasks.poll()) != null) {
        ActivityTask<ForumActivityContext> activityTask = task.getTask();
        //
        ExoSocialActivity got = ActivityExecutor.execute(activityTask, task.getContext());
        //
        if (activityTask instanceof PostActivityTask) {
          //
          PostActivityTask task_ = PostActivityTask.ADD_POST;
          if (got != null && activityTask.equals(task_)) {
            //
            ForumActivityUtils.takeCommentBack(task.getContext().getPost(), got);
          }
        } else if (activityTask instanceof TopicActivityTask) {
          //
          TopicActivityTask task_ = TopicActivityTask.ADD_TOPIC;
          if (got != null && activityTask.equals(task_)) {
            ForumActivityUtils.takeActivityBack(task.getContext().getTopic(), got);
          }
        }
      }
    } catch (Exception e) {
      LOG.warn("Running task of forum activity unsuccessful.");
      LOG.debug(e.getMessage(), e);
    } finally {
      isDone = true;
    }
  }

  public static class Task<T> {
    private ForumActivityContext ctx;
    private ActivityTask<T>      task;

    public Task(ForumActivityContext ctx, ActivityTask<T> task) {
      this.ctx = ctx;
      this.task = task;
    }

    public ForumActivityContext getContext() {
      return ctx;
    }

    public ActivityTask<T> getTask() {
      return task;
    }
  }

}
