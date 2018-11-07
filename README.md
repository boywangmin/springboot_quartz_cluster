## SpringBoot整合Quartz集群方案
#### Quartz集群需要数据库的支持（JobStore TX或者JobStoreCMT），从本质上来说，是使集群上的每一个节点通过共享同一个数据库来工作的。
#### Quartz集群和Redis这样的集群实现方式不一样，Redis集群需要节点之间通信，各节点需要知道其他节点的状况，而Quartz集群的实现方式在于11张表，集群节点相互之间不通信，而是通过定时任务持久化加锁的方式来实现集群。
#### Quartz可以通过jdbc直连连接到MYSQL数据库，读取配置在数据库里的job初始化信息，并且把job通过java序列化到数据库里，这样就使得每个job信息得到了持久化，即使在jvm或者容器挂掉的情况下，也能通过数据库感知到其他job的状态和信息。
#### 整合步骤
1. 创建quartz要用的数据库表（共11张表，[表具体分析](https://segmentfault.com/a/1190000014550260)）

表名 | 说明
---|---
QRTZ_BLOB_TRIGGERS | Trigger作为Blob类型存储
QRTZ_CALENDARS | 存储Quartz的Calendar信息
QRTZ_CRON_TRIGGERS | 存储CronTrigger，包括Cron表达式和时区信息
QRTZ_FIRED_TRIGGERS | 存储与已触发的Trigger相关的状态信息，以及相联Job的执行信息
QRTZ_JOB_DETAILS | 存储每一个已配置的Job的详细信息
QRTZ_LOCKS | 存储程序的悲观锁的信息
QRTZ_PAUSED_TRIGGER_GRPS | 存储已暂停的Trigger组的信息
QRTZ_SCHEDULER_STATE | 存储少量的有关Scheduler的状态信息，和别的Scheduler实例
QRTZ_SIMPLE_TRIGGERS | 存储简单的Trigger，包括重复次数、间隔、以及已触的次数
QRTZ_SIMPROP_TRIGGERS | 存储CalendarIntervalTrigger和DailyTimeIntervalTrigger两种类型的触发器
QRTZ_TRIGGERS | 存储已配置的Trigger的信息

2. 引入关键依赖

```
        <dependency>
			<groupId>org.quartz-scheduler</groupId>
			<artifactId>quartz</artifactId>
			<version>${quartz.version}</version>
		</dependency>

		<dependency>
			<groupId>org.springframework</groupId>
			<artifactId>spring-context-support</artifactId>
		</dependency>

		<dependency>
			<groupId>org.springframework</groupId>
			<artifactId>spring-tx</artifactId>
		</dependency>
```
    
3. 配置文件：quartz.properties

```
#============================================================================
# Configure Main Scheduler Properties
#============================================================================
# 属性可为任何值，用在 JDBC JobStore 中来唯一标识实例，但是所有集群节点中必须相同
org.quartz.scheduler.instanceName = MyClusteredScheduler
# 属性为 AUTO即可，基于主机名和时间戳来产生实例 ID
org.quartz.scheduler.instanceId = AUTO
org.quartz.scheduler.makeSchedulerThreadDaemon = true
# 是否使用自己的配置文件
org.quartz.jobStore.useProperties=true


#============================================================================
# Configure ThreadPool
#============================================================================
# 线程池的实现类
org.quartz.threadPool.class = org.quartz.simpl.SimpleThreadPool
# 是否为守护线程
org.quartz.threadPool.makeThreadsDaemons = true
# 线程数
org.quartz.threadPool.threadCount = 25
# 线程的优先级
org.quartz.threadPool.threadPriority = 5

#============================================================================
# Configure JobStore
#============================================================================
# 属性为 JobStoreTX，将任务持久化到数据中。因为集群中节点依赖于数据库来传播 Scheduler 实例的状态，你只能在使用 JDBC JobStore 时应用 Quartz 集群，这意味着你必须使用 JobStoreTX 或是 JobStoreCMT 作为 Job 存储；你不能在集群中使用 RAMJobStore。
org.quartz.jobStore.class = org.quartz.impl.jdbcjobstore.JobStoreTX 
org.quartz.jobStore.driverDelegateClass = org.quartz.impl.jdbcjobstore.StdJDBCDelegate
# 数据库中quartz表的表名前缀
org.quartz.jobStore.tablePrefix = qrtz_
# 属性为 true，你就告诉了 Scheduler 实例要它参与到一个集群当中。这一属性会贯穿于调度框架的始终，用于修改集群环境中操作的默认行为。
org.quartz.jobStore.isClustered = true
org.quartz.jobStore.dataSource = myDs
org.quartz.jobStore.misfireThreshold = 60000
# 属性定义了Scheduler 实例检入到数据库中的频率(单位：毫秒)。Scheduler 检查是否其他的实例到了它们应当检入的时候未检入；这能指出一个失败的 Scheduler 实例，且当前 Scheduler 会以此来接管任何执行失败并可恢复的 Job。通过检入操作，Scheduler 也会更新自身的状态记录。clusterChedkinInterval 越小，Scheduler 节点检查失败的 Scheduler 实例就越频繁。默认值是 15000 (即15 秒)。
org.quartz.jobStore.clusterCheckinInterval = 5000


#============================================================================
# Configure Datasources  
#============================================================================
org.quartz.dataSource.myDs.driver = com.mysql.jdbc.Driver
org.quartz.dataSource.myDs.URL = jdbc:mysql://localhost:3306/cluster_quartz?characterEncoding=utf8&useSSL=false
org.quartz.dataSource.myDs.user = root
org.quartz.dataSource.myDs.password = l3%ausn1ySKm!a2#
org.quartz.dataSource.myDs.maxConnections = 5
org.quartz.dataSource.myDs.validationQuery = select 1
```

4. SpringJobFactory：该任务工厂类可以使具体的Job交给spring来管理，因此我们可以在Job类上注入我们需要的业务级别的代码（service类）
    
```
@Component
public class SpringJobFactory extends AdaptableJobFactory {

    @Autowired
    private AutowireCapableBeanFactory capableBeanFactory;

    @Override
    protected Object createJobInstance(TriggerFiredBundle bundle) throws Exception {
        Object jobInstance = super.createJobInstance(bundle);
        capableBeanFactory.autowireBean(jobInstance);
        return jobInstance;
    }

}
```

5. SchedulerConfig：向spring注册SchedulerFactoryBean
    
```
Slf4j
@Configuration
@SuppressWarnings("ALL")
public class SchedulerConfig {

    @Bean
    public Properties properties() throws IOException {
        Properties prop = new Properties();
        prop.load(new ClassPathResource("/quartz.properties").getInputStream());
        return prop;
    }

    @Autowired
    private SpringJobFactory springJobFactory;

    @Bean
    public SchedulerFactoryBean schedulerFactoryBean() throws IOException {
        SchedulerFactoryBean schedulerFactoryBean = new SchedulerFactoryBean();
        //是否覆盖就任务
        schedulerFactoryBean.setOverwriteExistingJobs(true);
        //延迟多久启动
        schedulerFactoryBean.setStartupDelay(10);
        //设置基本的配置
        schedulerFactoryBean.setQuartzProperties(properties());
        //是否自动启动
        schedulerFactoryBean.setAutoStartup(Boolean.TRUE);
        //必须设置，具体的任务实例才能交给spring管理
        schedulerFactoryBean.setJobFactory(springJobFactory);
        return schedulerFactoryBean;
    }
}
```

6. JobConfig：任务配置实体类

```
@Data
@AllArgsConstructor
@NoArgsConstructor
public class JobConfig {

    /**
     * 任务id
     */
    private String id;

    /**
     * 任务类
     */
    private Class JobClass;

    /**
     * 所属组名
     */
    private String groupName;

    /**
     * 定时触发时间
     */
    private String cronTime;

    /**
     * 定时任务所需的额外数据
     */
    private Map<String, Object> jobData;
}
```

7. SchedulerHelper：任务操作类

```
@Slf4j
@Component
public class SchedulerHelper {

    @Autowired
    private Scheduler scheduler;

    /**
     * 创建任务
     * @param jobConfig
     * @return
     */
    public Boolean createScheduler(JobConfig jobConfig) {
        JobDataMap jobDataMap = new JobDataMap();
        jobDataMap.putAll(jobConfig.getJobData());
        JobDetail job = JobBuilder.newJob(jobConfig.getJobClass())
                .withIdentity(jobConfig.getId(), jobConfig.getGroupName())
                .withDescription(jobConfig.toString())
                .usingJobData(jobDataMap)
                .build();
        CronScheduleBuilder scheduleBuilder = CronScheduleBuilder.cronSchedule(jobConfig.getCronTime());
        CronTrigger cronTrigger = TriggerBuilder.newTrigger().withIdentity(jobConfig.getId(), jobConfig.getGroupName()) .withSchedule(scheduleBuilder).build();
        try {
            scheduler.scheduleJob(job,cronTrigger);
            log.info("任务创建成功：{} : {}", jobConfig.getGroupName(), jobConfig.getId());
            return Boolean.TRUE;
        } catch (SchedulerException e) {
            log.info("任务创建失败：{} : {}, cause : {}", jobConfig.getGroupName(), jobConfig.getId(), Throwables.getStackTraceAsString(e));
            return Boolean.FALSE;
        }
    }

    /**
     * 删除任务
     * @param jobConfig
     * @return
     */
    public Boolean deleteScheduler(JobConfig jobConfig) {
        TriggerKey triggerKey = new TriggerKey(jobConfig.getId(), jobConfig.getGroupName());
        try {
            Trigger keyTrigger = scheduler.getTrigger(triggerKey);
            if (keyTrigger != null) {
                scheduler.unscheduleJob(triggerKey);
                log.info("任务删除成功：{} : {}", jobConfig.getGroupName(), jobConfig.getId());
                return Boolean.TRUE;
            } else {
                log.info("任务不存在 ：{} : {}", jobConfig.getGroupName(), jobConfig.getGroupName());
                return Boolean.FALSE;
            }
        } catch (Exception e) {
            log.info("任务删除失败：{} : {}, cause {}", jobConfig.getGroupName(), jobConfig.getId(), Throwables.getStackTraceAsString(e));
            return Boolean.FALSE;
        }
    }

    /**
     * 更新任务
     * @param jobConfig
     * @return
     */
    public Boolean updateScheduler(JobConfig jobConfig) {
        Boolean deleteScheduler = deleteScheduler(jobConfig);
        if (!deleteScheduler) {
            log.info("任务更新失败：{} : {}", jobConfig.getGroupName(), jobConfig.getId());
            return Boolean.FALSE;
        }
        Boolean createScheduler = createScheduler(jobConfig);
        if (!createScheduler) {
            log.info("任务更新失败：{} : {}", jobConfig.getGroupName(), jobConfig.getId());
            return Boolean.FALSE;
        }
        log.info("任务更新成功：{} : {}", jobConfig.getGroupName(), jobConfig.getId());
        return Boolean.TRUE;
    }
}
```

8. DemoJob：具体执行的任务
    
```
@Slf4j
@Component
public class DemoJob implements Job {

    @Override
    public void execute(JobExecutionContext jobExecutionContext) throws JobExecutionException {
        JobDetail jobDetail = jobExecutionContext.getJobDetail();
        log.info("当前时间为{}, 当前jobDetail : {}", Date.from(Instant.now()), jobDetail);
    }
}
```

9. JobController：任务控制器（可以添加、修改、删除任务）

```
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
```

#### 说明
本项目完全参考GitHub上的这个项目https://github.com/SamlenTsoi/springboot-mybatis-quartz
