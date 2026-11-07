<template>
  <div class="dashboard">
    <div class="dashboard-header">违章检测数据统计</div>
    <el-row :gutter="20" class="dashboard-content">
      <el-col :span="12">
        <div class="chart-container">
          <div id="detectionPieChart" class="chart"></div>
        </div>
      </el-col>
      <el-col :span="12">
        <div class="chart-container">
          <div id="visitorLineChart" class="chart"></div>
        </div>
      </el-col>
      <el-col :span="12">
        <div class="chart-container">
          <div id="falseDetectionLineChart" class="chart"></div>
        </div>
      </el-col>
      <el-col :span="12">
        <div class="chart-container">
          <div id="withdrawalBarChart" class="chart"></div>
        </div>
      </el-col>
    </el-row>
  </div>
</template>


<script>
import { getDashboardData } from '../../api/admin'
export default {
  name: "DetectionDashboard",
  data() {
    return {
      // 饼图数据 - 检测结果分布
      pieChartData: {
        title: {
          text: '检测结果分布',
          left: 'center',
          textStyle: {
            fontSize: 16,
            color: '#333'
          }
        },
        tooltip: {
          trigger: 'item',
          formatter: '{a} <br/>{b}: {c} ({d}%)'
        },
        legend: {
          orient: 'vertical',
          right: 10,
          top: 'center',
          textStyle: {
            color: '#666'
          },
          data: ['未检测', 'AI生成图片','等待审核', '确定违章', '非违章']
        },
        color: ['#FF9F43', '#FF6384', '#2e8b57','#36A2EB', '#4BC0C0'],
        series: [
          {
            name: '检测结果',
            type: 'pie',
            radius: ['50%', '70%'],
            center: ['40%', '50%'],
            avoidLabelOverlap: false,
            itemStyle: {
              borderRadius: 5,
              borderColor: '#fff',
              borderWidth: 2
            },
            label: {
              show: false,
              position: 'center'
            },
            emphasis: {
              label: {
                show: true,
                fontSize: '18',
                fontWeight: 'bold'
              }
            },
            labelLine: {
              show: false
            },
            data: [
              { value: 1548, name: '未检测' },
              { value: 535, name: 'AI生成图片' },
              { value: 535, name: '等待审核' },
              { value: 510, name: '确定违章' },
              { value: 634, name: '非违章' }
            ]
          }
        ]
      },
      
      // 访问量折线图数据
      visitorData: {
        title: {
          text: '近七日访问量统计',
          left: 'center',
          textStyle: {
            fontSize: 16,
            color: '#333'
          }
        },
        tooltip: {
          trigger: 'axis',
          axisPointer: {
            type: 'shadow'
          }
        },
        grid: {
          left: '3%',
          right: '4%',
          bottom: '3%',
          containLabel: true
        },
        xAxis: {
          type: 'category',
          boundaryGap: false,
          data: this.getLast7Days(),
          axisLine: {
            lineStyle: {
              color: '#999'
            }
          },
          axisLabel: {
            color: '#666'
          }
        },
        yAxis: {
          type: 'value',
          axisLine: {
            lineStyle: {
              color: '#999'
            }
          },
          axisLabel: {
            color: '#666',
            formatter: '{value}'
          },
          splitLine: {
            lineStyle: {
              color: '#eee'
            }
          }
        },
        series: [
          {
            name: '访问量',
            type: 'line',
            data: [1200, 1800, 1500, 2100, 1900, 2300, 2500],
            smooth: true,
            symbol: 'circle',
            symbolSize: 8,
            itemStyle: {
              color: '#FF9F43',
              borderColor: '#fff',
              borderWidth: 2
            },
            lineStyle: {
              width: 3,
              color: '#FF9F43'
            },
            areaStyle: {
              color: new this.$echarts.graphic.LinearGradient(0, 0, 0, 1, [
                { offset: 0, color: 'rgba(255, 159, 67, 0.3)' },
                { offset: 1, color: 'rgba(255, 159, 67, 0.1)' }
              ])
            }
          }
        ]
      },
      
      // 折线图数据 - 误报和AI生成图片数量（误报改为直线）
      falseDetectionData: {
        title: {
          text: '近七日误报与AI生成统计',
          left: 'center',
          textStyle: {
            fontSize: 16,
            color: '#333'
          }
        },
        tooltip: {
          trigger: 'axis',
          axisPointer: {
            type: 'cross',
            label: {
              backgroundColor: '#6a7985'
            }
          }
        },
        legend: {
          data: ['误报(非违章)', 'AI生成图片'],
          top: 20,
          textStyle: {
            color: '#666'
          }
        },
        grid: {
          left: '3%',
          right: '4%',
          bottom: '3%',
          containLabel: true
        },
        xAxis: {
          type: 'category',
          boundaryGap: false,
          data: this.getLast7Days(),
          axisLine: {
            lineStyle: {
              color: '#999'
            }
          },
          axisLabel: {
            color: '#666'
          }
        },
        yAxis: {
          type: 'value',
          axisLine: {
            lineStyle: {
              color: '#999'
            }
          },
          axisLabel: {
            color: '#666'
          },
          splitLine: {
            lineStyle: {
              color: '#eee'
            }
          }
        },
        color: ['#36A2EB', '#FF6384'],
        series: [
          {
            name: '误报(非违章)',
            type: 'line',
            smooth: false, // 改为直线
            symbol: 'circle',
            symbolSize: 8,
            itemStyle: {
              borderColor: '#fff',
              borderWidth: 2
            },
            lineStyle: {
              width: 3
            },
            data: [140, 90, 120, 110, 130, 100, 150]
          },
          {
            name: 'AI生成图片',
            type: 'line',
            smooth: false, // AI生成
            symbol: 'triangle',
            symbolSize: 8,
            itemStyle: {
              borderColor: '#fff',
              borderWidth: 2
            },
            lineStyle: {
              width: 3
            },
            data: [80, 95, 70, 110, 60, 85, 90]
          }
        ]
      },
      
      // 柱状图数据 - 近七日提现金额（改为柱状图）
      withdrawalData: {
        title: {
          text: '近七日提现金额',
          left: 'center',
          textStyle: {
            fontSize: 16,
            color: '#333'
          }
        },
        tooltip: {
          trigger: 'axis',
          axisPointer: {
            type: 'shadow'
          },
          formatter: '提现金额: {c0}元'
        },
        grid: {
          left: '3%',
          right: '4%',
          bottom: '3%',
          containLabel: true
        },
        xAxis: {
          type: 'category',
          data: this.getLast7Days(),
          axisLine: {
            lineStyle: {
              color: '#999'
            }
          },
          axisLabel: {
            color: '#666'
          },
          axisTick: {
            alignWithLabel: true
          }
        },
        yAxis: {
          type: 'value',
          name: '金额(元)',
          axisLine: {
            lineStyle: {
              color: '#999'
            },
            show: true
          },
          axisLabel: {
            color: '#666',
            formatter: '{value}'
          },
          splitLine: {
            lineStyle: {
              color: '#eee'
            }
          }
        },
        color: ['#B22222'],
        series: [
          {
            name: '提现金额',
            type: 'bar',
            barWidth: '40%',
            data: [500, 800, 1200, 600, 900, 1500, 1100],
            itemStyle: {
              borderRadius: [4, 4, 0, 0],
              borderColor: '#fff',
              borderWidth: 1
            },
            label: {
              show: true,
              position: 'top',
              color: '#666'
            },
            // markPoint: {
            //   data: [
            //     { type: 'max', name: '最大值' },
            //     { type: 'min', name: '最小值' }
            //   ]
            // },
            markLine: {
              data: [{ type: 'average', name: '平均值' }]
            }
          }
        ]
      }
    };
  },
  methods: {
    // 获取最近7天日期
    getLast7Days() {
      const dates = [];
      for (let i = 6; i >= 0; i--) {
        const date = new Date();
        date.setDate(date.getDate() - i);
        dates.push(`${date.getMonth() + 1}/${date.getDate()}`);
      }
      return dates;
    },
    
    // 初始化图表
    initCharts() {
      // 饼图
      const pieChart = this.$echarts.init(document.getElementById('detectionPieChart'));
      pieChart.setOption(this.pieChartData);
      
      // 访问量折线图
      const visitorChart = this.$echarts.init(document.getElementById('visitorLineChart'));
      visitorChart.setOption(this.visitorData);
      
      // 误报和AI生成折线图
      const falseDetectionChart = this.$echarts.init(document.getElementById('falseDetectionLineChart'));
      falseDetectionChart.setOption(this.falseDetectionData);
      
      // 提现柱状图
      const withdrawalChart = this.$echarts.init(document.getElementById('withdrawalBarChart'));
      withdrawalChart.setOption(this.withdrawalData);
      
      // 窗口大小变化时重绘图表
      window.addEventListener('resize', function() {
        pieChart.resize();
        visitorChart.resize();
        falseDetectionChart.resize();
        withdrawalChart.resize();
      });
    },
    
    // 模拟从后端获取数据
    fetchDataFromBackend() {
   
      getDashboardData().then(response => {
        // 更新饼图数据
        response=response.data
        this.pieChartData.series[0].data = [
          { value: response.undetected, name: '未检测' },
          { value: response.aiDetected, name: 'AI生成图片' },
           { value: response.waitProcess, name: '等待审核' },
          { value: response.violation, name: '确定违章' },
          { value: response.nonViolation, name: '非违章' }
        ];
        
        // 更新访问量数据
        this.visitorData.series[0].data = response.visitorData;
        
        // 更新误报和AI数据
        this.falseDetectionData.series[0].data = response.falseReportData;
        this.falseDetectionData.series[1].data = response.aiData;
        
        // 更新提现数据
        this.withdrawalData.series[0].data = response.withdrawalData;
        
        // 初始化图表
        this.initCharts();
      });
      
      
    //   // 这里我们直接初始化图表
    //   this.initCharts();
    }
  },
  mounted() {
    this.fetchDataFromBackend();
  }
};
</script>

<style lang="scss" scoped>
.dashboard {
  width: 98%;
  height: 100%;
  margin: 0 auto;
  padding: 20px;
  box-sizing: border-box;
}

.dashboard-header {
  width: 100%;
  height: 60px;
  font-size: 24px;
  line-height: 60px;
  text-align: center;
  letter-spacing: 2px;
  color: #333;
  margin-bottom: 20px;
  font-weight: bold;
}

.dashboard-content {
  width: 100%;
  height: calc(100% - 80px);
}

.chart-container {
  width: 100%;
  height: 400px;
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 2px 12px 0 rgba(0, 0, 0, 0.1);
  margin-bottom: 20px;
  padding: 15px;
  box-sizing: border-box;
  transition: all 0.3s ease;

  &:hover {
    box-shadow: 0 4px 16px 0 rgba(0, 0, 0, 0.15);
    transform: translateY(-2px);
  }
}

.chart {
  width: 100%;
  height: 100%;
}

.el-col {
  padding-left: 10px !important;
  padding-right: 10px !important;
}
</style>