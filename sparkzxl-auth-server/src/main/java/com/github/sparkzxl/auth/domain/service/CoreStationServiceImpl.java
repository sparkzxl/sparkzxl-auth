package com.github.sparkzxl.auth.domain.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.pagehelper.PageInfo;
import com.github.sparkzxl.auth.application.service.ICoreStationService;
import com.github.sparkzxl.auth.domain.repository.ICoreStationRepository;
import com.github.sparkzxl.auth.domain.repository.ISegmentIdRepository;
import com.github.sparkzxl.auth.infrastructure.constant.BizConstant;
import com.github.sparkzxl.auth.infrastructure.convert.CoreStationConvert;
import com.github.sparkzxl.auth.infrastructure.entity.CoreStation;
import com.github.sparkzxl.auth.infrastructure.mapper.CoreStationMapper;
import com.github.sparkzxl.auth.interfaces.dto.station.StationQueryDTO;
import com.github.sparkzxl.auth.interfaces.dto.station.StationSaveDTO;
import com.github.sparkzxl.auth.interfaces.dto.station.StationUpdateDTO;
import com.github.sparkzxl.database.base.service.impl.SuperCacheServiceImpl;
import com.github.sparkzxl.database.dto.PageParams;
import com.github.sparkzxl.database.utils.PageInfoUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * description: 岗位 服务实现类
 *
 * @author charles.zhou
 * @date 2020-06-07 13:37:46
 */
@Service
public class CoreStationServiceImpl extends SuperCacheServiceImpl<CoreStationMapper, CoreStation> implements ICoreStationService {

    @Autowired
    private ICoreStationRepository coreStationRepository;

    @Autowired
    private ISegmentIdRepository segmentIdRepository;


    @Override
    public PageInfo<CoreStation> getStationPageList(PageParams<StationQueryDTO> params) {
        return PageInfoUtils.pageInfo(coreStationRepository.getStationPageList(params.getPageNum(),
                params.getPageSize(), params.getModel().getName(),
                params.getModel().getOrg()));
    }

    @Override
    public boolean saveCoreStation(StationSaveDTO stationSaveDTO) {
        CoreStation coreStation = CoreStationConvert.INSTANCE.convertCoreStation(stationSaveDTO);
        long id = segmentIdRepository.getSegmentId("core_station").longValue();
        coreStation.setId(id);
        return save(coreStation);
    }

    @Override
    public boolean updateCoreStation(StationUpdateDTO stationUpdateDTO) {
        CoreStation coreStation = CoreStationConvert.INSTANCE.convertCoreStation(stationUpdateDTO);
        return updateById(coreStation);
    }

    @Override
    public CoreStation getCoreStationByName(String stationName) {
        LambdaQueryWrapper<CoreStation> stationLambdaQueryWrapper = new LambdaQueryWrapper<>();
        stationLambdaQueryWrapper.eq(CoreStation::getName, stationName);
        stationLambdaQueryWrapper.eq(CoreStation::getStatus, true).last("limit 1");
        return getOne(stationLambdaQueryWrapper);
    }


    @Override
    public boolean deleteCoreStation(List<Long> ids) {
        return coreStationRepository.deleteCoreStation(ids);
    }

    @Override
    protected String getRegion() {
        return BizConstant.STATION;
    }
}
