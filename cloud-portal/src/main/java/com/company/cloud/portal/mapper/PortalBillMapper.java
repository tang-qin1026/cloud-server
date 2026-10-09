package com.company.cloud.portal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.cloud.portal.entity.PortalBill;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;

/**
 * b_bill 数据访问。
 */
@Mapper
public interface PortalBillMapper extends BaseMapper<PortalBill> {

    /** 账单号序列（并发安全，不依赖应用侧自增计数）。 */
    @Select("SELECT nextval('portal_bill_seq')")
    long nextBillSeq();

    @Select("SELECT * FROM b_bill WHERE bill_no = #{billNo}")
    PortalBill selectByBillNo(@Param("billNo") String billNo);

    /** 扣费/退款结果收敛。 */
    @Update("""
            UPDATE b_bill
            SET status = #{status},
                fail_code = #{failCode},
                retry_count = #{retryCount},
                updated_at = now()
            WHERE bill_no = #{billNo}
            """)
    int updateResult(@Param("billNo") String billNo,
                     @Param("status") int status,
                     @Param("failCode") Integer failCode,
                     @Param("retryCount") int retryCount);

    /**
     * 累计已成功退款金额（退款单 type=3 且 status=1 已入账）。
     * 用于「累计退款不得超过原单金额」的本侧前置校验。
     */
    @Select("""
            SELECT COALESCE(SUM(amount_cents), 0) FROM b_bill
            WHERE related_bill_no = #{originalBillNo} AND type = 3 AND status = 1
            """)
    long sumRefunded(@Param("originalBillNo") String originalBillNo);

    /**
     * 对账上报取数（契约 §6）：指定账期 + 产品编码，只取发生过真实资金流水的账单
     * （已入账 1 / 已退款 2）。status=0 的待扣账单门户侧没有流水，上报只会制造
     * 「仅产品侧有」的噪声差异，故排除（数量会打日志，便于发现长期卡住未扣的账单）。
     */
    @Select("""
            SELECT * FROM b_bill
            WHERE product_code = #{productCode} AND biz_date = #{bizDate} AND status IN (1, 2)
            ORDER BY bill_no
            """)
    List<PortalBill> selectReportable(@Param("productCode") String productCode,
                                      @Param("bizDate") LocalDate bizDate);

    /** 当日待扣（未成功）账单数量，用于对账日志提示。 */
    @Select("""
            SELECT COUNT(*) FROM b_bill
            WHERE product_code = #{productCode} AND biz_date = #{bizDate} AND status = 0
            """)
    long countPending(@Param("productCode") String productCode, @Param("bizDate") LocalDate bizDate);
}
