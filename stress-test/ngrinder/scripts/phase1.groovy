import static net.grinder.script.Grinder.grinder
import net.grinder.script.GTest
import net.grinder.script.Grinder
import net.grinder.plugin.http.HTTPRequest
import net.grinder.plugin.http.HTTPPluginControl
import HTTPClient.NVPair
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import net.grinder.scriptengine.groovy.junit.GrinderRunner
import net.grinder.scriptengine.groovy.junit.annotation.BeforeProcess
import net.grinder.scriptengine.groovy.junit.annotation.BeforeThread
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ThreadLocalRandom

/**
 * 대기열 시스템 및 좌석 조회 통합 부하 테스트 (Phase 1)
 * 흐름: 로그인 -> 대기열 진입 -> ACTIVE 전환 폴링 -> Redis 좌석 조회
 */
@RunWith(GrinderRunner)
class TurnstileIntegratedTest {

    public static GTest test1, test2, test3, test4
    public static HTTPRequest reqLogin, reqEntry, reqStatus, reqSeats

    public static String baseUrl = "http://172.21.112.1:8080"

    @BeforeProcess
    public static void beforeProcess() {
        // 장시간 폴링(최대 60초)을 고려하여 커넥션 타임아웃 연장
        HTTPPluginControl.getConnectionDefaults().timeout = 6000

        test1 = new GTest(1, "1_Login_API")
        test2 = new GTest(2, "2_Queue_Entry_API")
        test3 = new GTest(3, "3_Queue_Status_API")
        test4 = new GTest(4, "4_Get_Seats_API")

        reqLogin = new HTTPRequest()
        reqEntry = new HTTPRequest()
        reqStatus = new HTTPRequest()
        reqSeats = new HTTPRequest()

        test1.record(reqLogin)
        test2.record(reqEntry)
        test3.record(reqStatus)
        test4.record(reqSeats)
    }

    @BeforeThread
    public void beforeThread() {
        grinder.statistics.delayReports = true
    }

    @Test
    public void doTest() {
        try {
            int randomUserId = ThreadLocalRandom.current().nextInt(1, 9001)
            String username = "user${randomUserId}"
            long concertId = 1L
            long scheduleId = 1L

            // Step 1: 로그인 및 토큰 발급
            def loginRes = reqLogin.POST("${baseUrl}/api/v1/auth/login",
                    JsonOutput.toJson([username: username]).getBytes(),
                    [new NVPair("Content-Type", "application/json")] as NVPair[])

            if (loginRes.statusCode != 200) {
                grinder.sleep(1000)
                return
            }
            String identityToken = new JsonSlurper().parseText(loginRes.text).accessToken

            // Step 2: 대기열 등록 (Redis Sorted Set 진입)
            NVPair[] authHeader = [
                    new NVPair("Content-Type", "application/json"),
                    new NVPair("Authorization", "Bearer ${identityToken}")
            ]
            def entryRes = reqEntry.POST("${baseUrl}/api/v1/queue/entry",
                    JsonOutput.toJson([concertId: concertId]).getBytes(), authHeader)

            if (entryRes.statusCode != 200 && entryRes.statusCode != 201) {
                grinder.sleep(1000)
                return
            }

            // Step 3: 대기열 활성화(ACTIVE) 상태 폴링
            // 0.5초 간격으로 최대 120번(약 60초) 재시도
            boolean isActive = false
            String queueToken = null
            for (int i = 0; i < 120; i++) {
                grinder.sleep(500)
                def statusRes = reqStatus.GET("${baseUrl}/api/v1/queue/status?concertId=${concertId}", null,
                        [new NVPair("Authorization", "Bearer ${identityToken}")] as NVPair[])

                if (statusRes.statusCode != 200) {
                    continue // 일시적 에러는 무시하고 다음 폴링 진행
                }

                def statusJson = new JsonSlurper().parseText(statusRes.text)
                if (statusJson.status == "ACTIVE") {
                    isActive = true
                    queueToken = statusJson.accessToken
                    break
                }
            }

            // Step 4: 좌석 조회 (ACTIVE 상태로 전환된 유저만 진행)
            if (isActive && queueToken != null) {
                def seatsRes = reqSeats.GET("${baseUrl}/api/v1/schedules/${scheduleId}/seats", null,
                        [new NVPair("Authorization", "Bearer ${queueToken}")] as NVPair[])

                if (seatsRes.statusCode != 200) {
                    grinder.sleep(1000)
                    return
                }
            } else {
                grinder.logger.warn(
                        "Polling timeout - user: ${username}, concertId: ${concertId}"
                )
            }
            grinder.sleep(2000) // 실제 유저가 좌석을 둘러보는 잔류 시간 시뮬레이션
        } catch (Exception e) {
            grinder.logger.error("Unexpected test flow error", e)
            grinder.sleep(1000)
        }
    }
}