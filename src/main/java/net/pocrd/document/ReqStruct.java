package net.pocrd.document;

import net.pocrd.annotation.Description;

import java.util.List;

/**
 * Created by rendong on 14-5-2.
 */
@Description("入参结构话描述")
public class ReqStruct {
    @Description("结构名")
    public String          name;
    @Description("分组名")
    public String          groupName;
    @Description("成员")
    public List<FieldInfo> fieldList;
}
