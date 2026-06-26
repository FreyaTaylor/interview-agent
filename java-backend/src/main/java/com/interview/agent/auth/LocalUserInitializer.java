package com.interview.agent.auth;

import com.interview.agent.user.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** self-hosted 单用户模式启动时确保本地用户存在。 */
@Component
public class LocalUserInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalUserInitializer.class);

    private final AppModeProperties mode;
    private final UserMapper userMapper;

    public LocalUserInitializer(AppModeProperties mode, UserMapper userMapper) {
        this.mode = mode;
        this.userMapper = userMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!mode.singleUser()) {
            return;
        }
        userMapper.ensureLocalUser();
        userMapper.syncUserIdSequence();
        log.info("[Auth] single_user 模式已确保本地用户 id=1");
    }
}