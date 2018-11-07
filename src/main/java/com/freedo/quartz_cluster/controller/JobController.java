package com.freedo.quartz_cluster.controller;

import com.google.common.collect.ImmutableMap;
import com.freedo.quartz_cluster.domain.JobConfig;
import com.freedo.quartz_cluster.helper.SchedulerHelper;
import com.freedo.quartz_cluster.job.DemoJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("job")
public class JobController {

    @Autowired
    private SchedulerHelper schedulerHelper;

    @PostMapping("add")
    public void createJob(@RequestParam("cron") String cron,
                          @RequestParam("id") String id,
                          @RequestParam("group") String group,
                          @RequestParam("extra") String extra) {
        JobConfig jobConfig = new JobConfig();
        jobConfig.setCronTime(cron);
        jobConfig.setGroupName(group);
        jobConfig.setId(id);
        jobConfig.setJobData(ImmutableMap.of("extra", extra));
        jobConfig.setJobClass(DemoJob.class);
        schedulerHelper.createScheduler(jobConfig);
    }

    @PostMapping("delete")
    public void deleteJob(@RequestParam("id") String id,
                          @RequestParam("group") String group) {
        JobConfig jobConfig = new JobConfig();
        jobConfig.setId(id);
        jobConfig.setGroupName(group);
        schedulerHelper.deleteScheduler(jobConfig);
    }

    @PostMapping("update")
    public void updateJob(@RequestParam("cron") String cron,
                          @RequestParam("id") String id,
                          @RequestParam("group") String group,
                          @RequestParam("extra") String extra) {
        JobConfig jobConfig = new JobConfig();
        jobConfig.setCronTime(cron);
        jobConfig.setGroupName(group);
        jobConfig.setId(id);
        jobConfig.setJobData(ImmutableMap.of("extra", extra));
        jobConfig.setJobClass(DemoJob.class);
        schedulerHelper.updateScheduler(jobConfig);
    }
}
