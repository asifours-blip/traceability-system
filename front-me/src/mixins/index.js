// mixins

import {dateTimeUtils} from '@/utils/commonUtil'
import {Message} from "element-ui";

let mixins =
    {
        data() {
            return {
                dateTimeUtils: dateTimeUtils
            }
        },
        methods: {
            goBack() {
                this.$router.go(-1)
            },
            // 复制效果
            copyData(text) {
                if (navigator.clipboard) {
                    navigator.clipboard.writeText(text).then(() => {
                        Message.success('复制成功')
                    }).catch(() => {
                        Message.error('复制失败')
                    })
                } else {
                    var textarea = document.createElement('textarea')
                    textarea.textContent = text
                    textarea.style.position = 'fixed'
                    textarea.style.top = 0
                    textarea.style.left= 0
                }
            },
        },
    }
export default mixins